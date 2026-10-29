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
    fun `the themes are told apart, not merely unequal`() {
        // **This is the report, and the old test could not see it.** "make themes truely different
        // in their colour schemes" - and all five primaries were browns within about twenty per
        // channel (`#8A6D3B`, `#8B5722`, `#7A5900`, `#6B4423`, `#4E3423`). Every one was distinct,
        // so `no two themes are the same palette` passed while a person choosing between them was
        // choosing between five shades of the same page.
        //
        // A colour is told apart from another when it is far enough away to name differently. The
        // measure is the sum of the channel distances, which is crude and exactly right here: it
        // says "these are not the same colour", which is the claim the report makes.
        fun distance(a: androidx.compose.ui.graphics.Color, b: androidx.compose.ui.graphics.Color): Int {
            val dr = kotlin.math.abs(a.red - b.red) * 255
            val dg = kotlin.math.abs(a.green - b.green) * 255
            val db = kotlin.math.abs(a.blue - b.blue) * 255
            return (dr + dg + db).toInt()
        }

        val primaries = AppTheme.entries.map { it.name to it.lightScheme().primary }
        for (i in primaries.indices) {
            for (j in i + 1 until primaries.size) {
                val (an, a) = primaries[i]
                val (bn, b) = primaries[j]
                val apart = distance(a, b)
                assertTrue(
                    "$an and $bn are only $apart apart, which is two shades of one colour",
                    apart >= 60,
                )
            }
        }
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
