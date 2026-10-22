package com.example.notes

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The theme definitions.
 *
 * What is worth checking here is only what could really go wrong: a palette that
 * says "light" while being unreadably dark, and two themes that are the same
 * palette under two names because one was copied and not edited. Everything
 * else about an enum of hardcoded colours is the compiler's job.
 *
 * Deliberately absent: a count of how many themes there are. That is a test
 * that breaks when the product changes and says nothing when the product is
 * wrong.
 */
class AppThemeTest {

    @Test
    fun `a light scheme is brighter than its own dark scheme`() {
        AppTheme.entries.forEach { theme ->
            // Given a theme with a light and a dark scheme
            val light = theme.lightScheme()
            val dark = theme.darkScheme()

            // When the brightness of their backgrounds is compared
            val lightBrightness = light.background.brightness()
            val darkBrightness = dark.background.brightness()

            // Then the light one is the brighter, and the dark one is really dark
            assertTrue(
                "${theme.name}: the light background ($lightBrightness) must be " +
                    "brighter than the dark one ($darkBrightness)",
                lightBrightness > darkBrightness,
            )
            assertTrue(
                "${theme.name}: the dark background ($darkBrightness) must be dark",
                darkBrightness < 0.3f,
            )
        }
    }

    @Test
    fun `no two themes are the same palette`() {
        // Given every theme's light palette
        val primaries = AppTheme.entries.map { it.lightScheme().primary }

        // When the themes are compared with each other
        val distinct = primaries.distinct()

        // Then each has a colour of its own, so a copied palette is caught
        assertEquals(
            "two themes share a primary colour: $primaries",
            primaries.size,
            distinct.size,
        )
    }

    @Test
    fun `no two themes have the same swatch`() {
        // Given the colour shown for each theme in the settings
        val swatches = AppTheme.entries.map { it.swatch }

        // When they are compared with each other
        val distinct = swatches.distinct()

        // Then each is its own, because the swatch is what a person picks by
        assertEquals(
            "two themes share a swatch: $swatches",
            swatches.size,
            distinct.size,
        )
    }
}

/** A rough perceptual brightness, used only to tell a light background from a dark one. */
private fun Color.brightness(): Float = 0.299f * red + 0.587f * green + 0.114f * blue
