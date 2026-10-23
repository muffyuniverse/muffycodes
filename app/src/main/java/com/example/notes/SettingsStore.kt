package com.example.notes

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The settings that are not about an account: the appearance, and how much
 * history is kept.
 *
 * Two separate lengths are kept, because the two histories have different
 * shapes — the account's own history records every edit and every move and
 * grows quickly, while a project's history grows only when the project is
 * synced. Zero means "keep everything", which is the default.
 *
 * A length is applied **on the next change**: nothing is pruned when the number
 * is typed, so shortening the history of a large tree is a decision whose cost
 * arrives with the next save rather than in the middle of a setting.
 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var theme: AppTheme by mutableStateOf(
        AppTheme.entries.firstOrNull { it.name == prefs.getString(KEY_THEME, null) }
            ?: AppTheme.CARAMEL
    )
        private set

    var useDark: Boolean by mutableStateOf(prefs.getBoolean(KEY_DARK, false))
        private set

    /** How many commits the account's history keeps. 0 keeps everything. */
    var appHistoryLength: Int by mutableStateOf(prefs.getInt(KEY_APP_HISTORY, 0))
        private set

    /** How many commits a project's history keeps. 0 keeps everything. */
    var projectHistoryLength: Int by mutableStateOf(prefs.getInt(KEY_PROJECT_HISTORY, 0))
        private set

    fun selectTheme(theme: AppTheme) {
        this.theme = theme
        prefs.edit().putString(KEY_THEME, theme.name).apply()
    }

    fun selectDarkVariant(useDark: Boolean) {
        this.useDark = useDark
        prefs.edit().putBoolean(KEY_DARK, useDark).apply()
    }

    /** Sets the account history length; a negative number means no limit. */
    fun keepAppHistory(value: Int) {
        val clean = value.coerceAtLeast(0)
        appHistoryLength = clean
        prefs.edit().putInt(KEY_APP_HISTORY, clean).apply()
    }

    /** Sets the project history length; a negative number means no limit. */
    fun keepProjectHistory(value: Int) {
        val clean = value.coerceAtLeast(0)
        projectHistoryLength = clean
        prefs.edit().putInt(KEY_PROJECT_HISTORY, clean).apply()
    }

    /**
     * Reads a length typed into a field. A blank field, or anything that is not
     * a number, means "keep everything".
     */
    companion object {
        const val KEY_THEME = "theme"
        const val KEY_DARK = "use_dark"
        const val KEY_APP_HISTORY = "app_history_length"
        const val KEY_PROJECT_HISTORY = "project_history_length"

        /** The number a typed field means. */
        fun parseLength(text: String): Int = text.trim().toIntOrNull()?.coerceAtLeast(0) ?: 0

        /** What a length looks like in its field: blank when there is no limit. */
        fun showLength(value: Int): String = if (value <= 0) "" else value.toString()
    }
}
