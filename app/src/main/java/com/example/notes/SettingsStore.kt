package com.example.notes

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The settings that are not about an account: the appearance, and how much
 * change history is kept.
 *
 * **One length, not two.** It used to be two — the account's own history and
 * each project's — and the difference was invisible in use: somebody setting a
 * limit is answering "how far back can I go?", which is one question. The number
 * now applies to this phone and to each project individually, which is what it
 * always meant.
 *
 * Zero means "keep everything", which is the default.
 *
 * A length is applied **on the next change**: nothing is pruned when the number
 * is typed, so shortening the history of a large tree is a decision whose cost
 * arrives with the next save rather than in the middle of a setting.
 */
class SettingsStore(context: Context, name: String = "settings") {

    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    var theme: AppTheme by mutableStateOf(
        AppTheme.entries.firstOrNull { it.name == prefs.getString(KEY_THEME, null) }
            ?: AppTheme.CARAMEL
    )
        private set

    var useDark: Boolean by mutableStateOf(prefs.getBoolean(KEY_DARK, false))
        private set

    /**
     * How many changes — local commits — are kept on this phone. 0 keeps all.
     *
     * Applies to the phone's own change history and to each project's individual
     * limit alike.
     */
    var historyLength: Int by mutableStateOf(prefs.getInt(KEY_HISTORY, legacyLength()))
        private set

    /**
     * Whether the trace is copied into Downloads while the app is open.
     *
     * **A development aid, and only a development aid.** A release build answers no
     * whatever is stored, because a release build has no business writing a running log
     * of a person's tapping into a folder other apps can read. In a debug build it
     * starts on: a session spent describing bugs by hand produces fewer of them per
     * hour than one where the app describes itself.
     */
    var liveTrace: Boolean by mutableStateOf(
        BuildConfig.DEBUG && prefs.getBoolean(KEY_LIVE, true),
    )
        private set

    /** True only where it can work at all. */
    val liveTraceAvailable: Boolean get() = BuildConfig.DEBUG

    fun keepLiveTrace(on: Boolean) {
        if (!liveTraceAvailable) return
        liveTrace = on
        prefs.edit().putBoolean(KEY_LIVE, on).apply()
    }

    fun selectTheme(theme: AppTheme) {
        // **A setting is a tap too.** These four went through this store, not the view
        // model, so they were the one family of presses that left no trace at all — and
        // "the theme switch did nothing" was unanswerable from a log that showed only
        // that the Settings screen was open.
        Trace.tap("theme", theme.name)
        this.theme = theme
        prefs.edit().putString(KEY_THEME, theme.name).apply()
        // **Answered here, because nothing else will.** The theme is applied by this store
        // and never passes through the view model, so the screen never redraws as a
        // consequence of the press and no state line could answer it. It sat unanswered
        // until the next unrelated screen change claimed it — a cancel or a back press
        // spending a tap it had nothing to do with.
        Trace.said("theme is now ${theme.name.lowercase()}")
    }

    fun selectDarkVariant(useDark: Boolean) {
        Trace.tap(if (useDark) "dark" else "light")
        this.useDark = useDark
        prefs.edit().putBoolean(KEY_DARK, useDark).apply()
        Trace.said(if (useDark) "dark colours" else "light colours")
    }

    /** Sets the change history length; a negative number means no limit. */
    fun keepHistory(value: Int) {
        val clean = value.coerceAtLeast(0)
        // Named with the number, because "0" and "" mean different things and the
        // difference is exactly what a person reports as "it keeps everything now".
        Trace.tap("history limit", if (clean == 0) "all" else clean.toString())
        historyLength = clean
        prefs.edit().putInt(KEY_HISTORY, clean).apply()
        Trace.said(if (clean == 0) "every version is kept" else "the last $clean versions are kept")
    }

    /**
     * How often a session that needs a person reminds them: minutes, default one hour.
     *
     * **A reminder, not a poll.** The poll is the agent working; this is the app asking somebody
     * to look at it, and it stops the moment the thing is dealt with. Zero means "say it once and
     * never again", which is a choice a person is allowed to make.
     */
    var remindMinutes: Int by mutableStateOf(
        prefs.getInt(KEY_REMIND, DEFAULT_REMIND_MINUTES)
    )
        private set

    /** Sets the reminder interval, clamped to minutes a person would recognise. */
    fun keepRemind(minutes: Int) {
        val clean = minutes.coerceIn(0, MAX_REMIND_MINUTES)
        Trace.tap("remind me", if (clean == 0) "once" else "$clean min")
        remindMinutes = clean
        prefs.edit().putInt(KEY_REMIND, clean).apply()
        Trace.said(if (clean == 0) "no reminders" else "a reminder every $clean minutes")
    }

    /**
     * The two lengths older builds stored separately, read once for the migration.
     *
     * The larger of the two is kept: a person who had set either history longer
     * than the other meant to be able to go back that far, and reading the smaller
     * would silently delete versions they had asked to keep.
     */
    private fun legacyLength(): Int =
        maxOf(prefs.getInt(KEY_APP_HISTORY, 0), prefs.getInt(KEY_PROJECT_HISTORY, 0))

    /**
     * Reads a length typed into a field. A blank field, or anything that is not
     * a number, means "keep everything".
     */
    companion object {
        const val KEY_THEME = "theme"
        const val KEY_DARK = "use_dark"
        const val KEY_LIVE = "live_trace"
        const val KEY_HISTORY = "history_length"

        /** The reminder interval, in minutes. */
        const val KEY_REMIND = "agent_remind_minutes"

        /** One hour: the design's default. */
        const val DEFAULT_REMIND_MINUTES = 60

        /** A week. Past this a reminder is a message from the past. */
        const val MAX_REMIND_MINUTES = 10_080

        /** The two keys older builds wrote; read once, then ignored. */
        const val KEY_APP_HISTORY = "app_history_length"
        const val KEY_PROJECT_HISTORY = "project_history_length"

        /** The number a typed field means. */
        fun parseLength(text: String): Int = text.trim().toIntOrNull()?.coerceAtLeast(0) ?: 0

        /** What a length looks like in its field: blank when there is no limit. */
        fun showLength(value: Int): String = if (value <= 0) "" else value.toString()
    }
}
