package com.example.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The theme definitions. Every theme must provide a usable light and dark scheme,
 * and the five of them must be distinct.
 */
class AppThemeTest {

    @Test
    fun `there are exactly five themes`() {
        assertEquals(5, AppTheme.entries.size)
    }

    @Test
    fun `every theme has a light and a dark scheme`() {
        AppTheme.entries.forEach { theme ->
            assertNotNull("${theme.name} light", theme.lightScheme())
            assertNotNull("${theme.name} dark", theme.darkScheme())
        }
    }

    @Test
    fun `the light scheme is light and the dark scheme is dark`() {
        AppTheme.entries.forEach { theme ->
            val light = theme.lightScheme()
            val dark = theme.darkScheme()
            assertTrue(
                "${theme.name}: light background should be bright",
                light.background.luminance() > dark.background.luminance(),
            )
            assertTrue(
                "${theme.name}: dark background should be dim",
                dark.background.luminance() < 0.3f,
            )
        }
    }

    @Test
    fun `each theme has its own primary colour`() {
        val primaries = AppTheme.entries.map { it.lightScheme().primary }
        assertEquals("themes should look different", primaries.size, primaries.distinct().size)
    }

    @Test
    fun `every theme has a distinct swatch`() {
        val swatches = AppTheme.entries.map { it.swatch }
        assertEquals(swatches.size, swatches.distinct().size)
    }

    @Test
    fun `every theme has a display name`() {
        AppTheme.entries.forEach { theme ->
            assertTrue(theme.displayName.isNotBlank())
        }
    }
}

/** A rough perceptual brightness, used only to tell light from dark backgrounds. */
private fun androidx.compose.ui.graphics.Color.luminance(): Float = 0.299f * red + 0.587f * green + 0.114f * blue
