package com.example.notes

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Whether Android will let an agent's polls out while the screen is off, and how to ask.
 *
 * **The one system setting this app genuinely needs, and the reason is in the design.** In Doze the
 * network is suspended for apps that are not exempt, so a poll fails — and this app has no fallback
 * provider by design, so a suspended network is not "a bit late", it is a session that stops on its
 * own and then tells the person it could not reach the provider. Nothing in the app can work around
 * that; only the person can, by exempting it.
 *
 * It is asked for **from settings, once** — not on launch and never in a loop. An app that opens
 * with a permissions dialog teaches people to dismiss permissions dialogs, and this one is worth
 * reading.
 */
object Battery {

    /** True when the app is already exempt from the battery optimiser. */
    fun exempt(context: Context): Boolean = try {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        power?.isIgnoringBatteryOptimizations(context.packageName) == true
    } catch (t: Throwable) {
        // No PowerManager is not a phone this app can be running on; "not exempt" is the answer
        // that offers the person the button rather than hiding it.
        false
    }

    /**
     * The screen to open, chosen **before** it is opened.
     *
     * `ACTION_REQUEST_IGNORE…` takes the person straight to this app's own row, and some
     * manufacturers remove it — so the fallback is the list of apps, which every build has. A person
     * sent to a list can still find the app; a press that opens nothing is the failure worth
     * avoiding. Deciding here rather than inside a `startActivity` try-block means the screen can
     * launch it the way this app launches every other system screen: through a result contract,
     * whose callback is where the state is read back.
     */
    fun intent(context: Context): Intent {
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        )
        val reachable = try {
            context.packageManager.queryIntentActivities(direct, 0).isNotEmpty()
        } catch (t: Throwable) {
            false
        }
        return if (reachable) direct else Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    }

    /** What to say once the person is back, in the app's own words. */
    fun said(context: Context): String =
        if (exempt(context)) "exempt now" else "still not exempt"
}
