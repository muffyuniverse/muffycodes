package com.example.notes

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Stores the chosen theme and whether the dark variant is used.
 *
 * The choice is kept in shared preferences so it survives a restart, and exposed as
 * Compose state so picking a theme updates the whole app immediately.
 */
class ThemePreferences(context: Context) {

    private val prefs = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)

    var theme: AppTheme by mutableStateOf(
        AppTheme.entries.firstOrNull { it.name == prefs.getString(KEY_THEME, null) } ?: AppTheme.CARAMEL
    )
        private set

    var useDark: Boolean by mutableStateOf(prefs.getBoolean(KEY_DARK, false))
        private set

    fun selectTheme(theme: AppTheme) {
        this.theme = theme
        prefs.edit().putString(KEY_THEME, theme.name).apply()
    }

    fun selectDarkVariant(useDark: Boolean) {
        this.useDark = useDark
        prefs.edit().putBoolean(KEY_DARK, useDark).apply()
    }

    private companion object {
        const val KEY_THEME = "theme"
        const val KEY_DARK = "use_dark"
    }
}
