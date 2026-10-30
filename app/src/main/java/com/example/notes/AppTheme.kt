package com.example.notes

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * The five colour themes, **and they are five different colours.**
 *
 * That was the report: "make themes truely different in their colour schemes, and rename dark
 * chocolate theme to chocolate". Measured before this change, all five primaries were browns within
 * about twenty of each other per channel (`#8A6D3B`, `#8B5722`, `#7A5900`, `#6B4423`, `#4E3423`) and
 * every light surface was a warm white - so a person choosing between them was choosing between five
 * shades of the same page, and the picker could not be used to find a favourite.
 *
 * Each theme is now its own hue family:
 *
 * | Theme | Family |
 * |---|---|
 * | Milk | plain white, with a neutral grey accent |
 * | Caramel | warm orange |
 * | Gold | yellow-gold, deliberately the one that stays close to the old palette |
 * | Brownie | true brown |
 * | Chocolate | near black, with the brown kept as its accent |
 *
 * **Two of these were renamed by what they look like, and the operator is why.** Brownie was a deep
 * plum and Chocolate was the brown; Milk was a sage green. A palette whose names do not describe it
 * is a palette nobody can ask for by name: "brownie should be brown theme from chocolate, and
 * chocolate should be near black theme". 
 *
 * **The error colour is the same in all five.** A red that a theme could change would be a theme
 * that could hide a failure.
 */
enum class AppTheme(
    val displayName: String,
    val swatch: Color,
) {
    // **The swatch is the colour the theme is named for**, because that is what the picker is
    // showing: a row of five small squares, and the only thing a person can tell them apart by.
    // Milk's was sage green and Brownie's was purple, so two of the five named colours did not
    // appear in the palette at all.
    MILK("Milk", Color(0xFFF2F2F2)),
    CARAMEL("Caramel", Color(0xFFE08A3C)),
    GOLD("Gold", Color(0xFFE8B923)),
    BROWNIE("Brownie", Color(0xFF493227)),
    CHOCOLATE("Chocolate", Color(0xFF1A1410));

    /** The light scheme for this theme. */
    fun lightScheme(): ColorScheme = when (this) {
        // **Plain white, the operator's own word**: "make milk a plain white theme". It was a cool
        // sage green - the palette was five coloured families and this was the pale green member,
        // which made the theme called Milk the least milk-like thing on the list. The accent is a
        // neutral slate rather than a colour, so nothing in the chrome competes with the page.
        MILK -> lightColorScheme(
            primary = Color(0xFF4A4A4A),
            onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFEDEDED),
            onPrimaryContainer = Color(0xFF1A1A1A),
            secondary = Color(0xFF5C5C5C),
            onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFFF0F0F0),
            onSecondaryContainer = Color(0xFF1A1A1A),
            tertiary = Color(0xFF6B6B6B),
            onTertiary = Color(0xFFFFFFFF),
            tertiaryContainer = Color(0xFFF2F2F2),
            onTertiaryContainer = Color(0xFF1A1A1A),
            background = Color(0xFFFFFFFF),
            onBackground = Color(0xFF1A1A1A),
            surface = Color(0xFFFFFFFF),
            onSurface = Color(0xFF1A1A1A),
            surfaceVariant = Color(0xFFF2F2F2),
            onSurfaceVariant = Color(0xFF5C5C5C),
            outline = Color(0xFFB8B8B8),
            error = Color(0xFFB3261E),
            onError = Color(0xFFFFFFFF),
        )

        // **Reddish orange, on a white page.** The operator: "Make caramel reddish orange", and "light
        // themes should have white screen backgrounds ... Difference should be in coloring of
        // nonbackground elements". Caramel was a muted brown-orange whose page was off-white, so the
        // theme was a tint applied to everything rather than a colour carried by the things on it.
        CARAMEL -> lightColorScheme(
            primary = Color(0xFFC2410C),
            onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFFFDBC8),
            onPrimaryContainer = Color(0xFF3A1200),
            secondary = Color(0xFFB04A24),
            onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFFFFDDD0),
            onSecondaryContainer = Color(0xFF3A1408),
            tertiary = Color(0xFF8A4B2A),
            onTertiary = Color(0xFFFFFFFF),
            tertiaryContainer = Color(0xFFFFE6D8),
            onTertiaryContainer = Color(0xFF33160A),
            background = Color(0xFFFFFFFF),
            onBackground = Color(0xFF201410),
            surface = Color(0xFFFFFFFF),
            onSurface = Color(0xFF201410),
            surfaceVariant = Color(0xFFF7E7DE),
            onSurfaceVariant = Color(0xFF5C4033),
            outline = Color(0xFFA97C63),
            error = Color(0xFFB3261E),
            onError = Color(0xFFFFFFFF),
        )

        // **Deep yellow, on a white page.** The operator: "gold deep yellow". It was a dark olive-gold
        // on a cream page - a colour that reads as "old paper" rather than as gold, because the page
        // carried as much of the tint as the elements did.
        GOLD -> lightColorScheme(
            primary = Color(0xFF8A6A00),
            onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFFFF0B8),
            onPrimaryContainer = Color(0xFF2A1F00),
            secondary = Color(0xFF7A5E12),
            onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFFFCEDC2),
            onSecondaryContainer = Color(0xFF241B02),
            tertiary = Color(0xFF6B5A1E),
            onTertiary = Color(0xFFFFFFFF),
            tertiaryContainer = Color(0xFFF5EAC8),
            onTertiaryContainer = Color(0xFF201A06),
            background = Color(0xFFFFFFFF),
            onBackground = Color(0xFF1E1A0C),
            surface = Color(0xFFFFFFFF),
            onSurface = Color(0xFF1E1A0C),
            surfaceVariant = Color(0xFFF5EFD6),
            onSurfaceVariant = Color(0xFF565024),
            outline = Color(0xFF9A8C4E),
            error = Color(0xFFB3261E),
            onError = Color(0xFFFFFFFF),
        )

        // **Brownie is the brown one now.** It was a deep plum - chosen, per the note at the top of
        // this file, so that "the dark member of the light set is not another brown" - and the
        // operator's correction is that the name wins: "brownie should be brown theme from
        // chocolate". These are Chocolate's colours, moved here wholesale.
        BROWNIE -> lightColorScheme(
            primary = Color(0xFF3E2A20),
            onPrimary = Color(0xFFFDFCFC),
            primaryContainer = Color(0xFFE5D8D1),
            onPrimaryContainer = Color(0xFF221711),
            secondary = Color(0xFF574538),
            onSecondary = Color(0xFFFDFCFC),
            secondaryContainer = Color(0xFFE3DAD3),
            onSecondaryContainer = Color(0xFF211812),
            tertiary = Color(0xFF633645),
            onTertiary = Color(0xFFFDFCFC),
            tertiaryContainer = Color(0xFFE6D1D8),
            onTertiaryContainer = Color(0xFF240F16),
            background = Color(0xFFFFFFFF),
            onBackground = Color(0xFF201713),
            surface = Color(0xFFFFFFFF),
            onSurface = Color(0xFF201713),
            surfaceVariant = Color(0xFFE6DFDB),
            onSurfaceVariant = Color(0xFF4E3E36),
            outline = Color(0xFF846D62),
            error = Color(0xFFB3261E),
            onError = Color(0xFFFFFFFF),
        )

        // **Black and white, with nothing else in it.** The operator: "Make chocolate theme show
        // white elements on black in dark mode", and its light half is the mirror: black elements on
        // a white page. Dark chocolate is the darkest of the five, so the name now describes a
        // *value* rather than a hue - which is the only way it can be told apart from Brownie, the
        // theme that took over its old brown.
        CHOCOLATE -> lightColorScheme(
            primary = Color(0xFF000000),
            onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFEDEDED),
            onPrimaryContainer = Color(0xFF000000),
            secondary = Color(0xFF2E2E2E),
            onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFFF0F0F0),
            onSecondaryContainer = Color(0xFF000000),
            tertiary = Color(0xFF4A4A4A),
            onTertiary = Color(0xFFFFFFFF),
            tertiaryContainer = Color(0xFFF5F5F5),
            onTertiaryContainer = Color(0xFF000000),
            background = Color(0xFFFFFFFF),
            onBackground = Color(0xFF000000),
            surface = Color(0xFFFFFFFF),
            onSurface = Color(0xFF000000),
            surfaceVariant = Color(0xFFE8E8E8),
            onSurfaceVariant = Color(0xFF333333),
            outline = Color(0xFF767676),
            error = Color(0xFFB3261E),
            onError = Color(0xFFFFFFFF),
        )
    }

    /** The dark scheme for this theme. */
    fun darkScheme(): ColorScheme = when (this) {
        // The same theme after dark: white becomes the neutral grey the other themes' surfaces are,
        // so Milk stays the one with no colour in it rather than turning green after sunset.
        MILK -> darkColorScheme(
            primary = Color(0xFFE0E0E0),
            onPrimary = Color(0xFF1A1A1A),
            primaryContainer = Color(0xFF3A3A3A),
            onPrimaryContainer = Color(0xFFEDEDED),
            secondary = Color(0xFFC8C8C8),
            onSecondary = Color(0xFF1A1A1A),
            secondaryContainer = Color(0xFF333333),
            onSecondaryContainer = Color(0xFFF0F0F0),
            tertiary = Color(0xFFB0B0B0),
            onTertiary = Color(0xFF1A1A1A),
            tertiaryContainer = Color(0xFF2E2E2E),
            onTertiaryContainer = Color(0xFFF2F2F2),
            background = Color(0xFF000000),
            onBackground = Color(0xFFE6E6E6),
            surface = Color(0xFF000000),
            onSurface = Color(0xFFE6E6E6),
            surfaceVariant = Color(0xFF2A2A2A),
            onSurfaceVariant = Color(0xFFBDBDBD),
            outline = Color(0xFF7A7A7A),
            error = Color(0xFFF2B8B5),
            onError = Color(0xFF601410),
        )

        CARAMEL -> darkColorScheme(
            primary = Color(0xFFDFB190),
            onPrimary = Color(0xFF45240C),
            primaryContainer = Color(0xFF734626),
            onPrimaryContainer = Color(0xFFF5E3D6),
            secondary = Color(0xFFD1AB9F),
            onSecondary = Color(0xFF40251C),
            secondaryContainer = Color(0xFF634136),
            onSecondaryContainer = Color(0xFFF1E0DA),
            tertiary = Color(0xFFD1C49F),
            onTertiary = Color(0xFF393118),
            tertiaryContainer = Color(0xFF5D5232),
            onTertiaryContainer = Color(0xFFF1EBDA),
            background = Color(0xFF000000),
            onBackground = Color(0xFFEBE5E0),
            surface = Color(0xFF000000),
            onSurface = Color(0xFFEBE5E0),
            surfaceVariant = Color(0xFF564539),
            onSurfaceVariant = Color(0xFFD1C5BD),
            outline = Color(0xFFA49184),
            error = Color(0xFFF2B8B5),
            onError = Color(0xFF601410),
        )

        GOLD -> darkColorScheme(
            primary = Color(0xFFF4E47B),
            onPrimary = Color(0xFF4E4404),
            primaryContainer = Color(0xFF827417),
            onPrimaryContainer = Color(0xFFFAF4D1),
            secondary = Color(0xFFD4C79B),
            onSecondary = Color(0xFF40381C),
            secondaryContainer = Color(0xFF675B32),
            onSecondaryContainer = Color(0xFFF1ECDA),
            tertiary = Color(0xFFB6D49B),
            onTertiary = Color(0xFF283918),
            tertiaryContainer = Color(0xFF46602E),
            onTertiaryContainer = Color(0xFFE5F1DA),
            background = Color(0xFF000000),
            onBackground = Color(0xFFEDEBDE),
            surface = Color(0xFF000000),
            onSurface = Color(0xFFEDEBDE),
            surfaceVariant = Color(0xFF595436),
            onSurfaceVariant = Color(0xFFD3D0BB),
            outline = Color(0xFFA9A47E),
            error = Color(0xFFF2B8B5),
            onError = Color(0xFF601410),
        )

        // Brownie's dark side, which is Chocolate's old dark side: warm brown light on a dark
        // brown page. It has to move with the light scheme or the theme would be brown by day and
        // plum after sunset.
        BROWNIE -> darkColorScheme(
            primary = Color(0xFFCDAA98),
            onPrimary = Color(0xFF2E190F),
            primaryContainer = Color(0xFF50372B),
            onPrimaryContainer = Color(0xFFEDDCD4),
            secondary = Color(0xFFC2B0A3),
            onSecondary = Color(0xFF2E2219),
            secondaryContainer = Color(0xFF493C31),
            onSecondaryContainer = Color(0xFFEADFD7),
            tertiary = Color(0xFFC69FAC),
            onTertiary = Color(0xFF2E1920),
            tertiaryContainer = Color(0xFF4B3039),
            onTertiaryContainer = Color(0xFFEAD7DD),
            background = Color(0xFF000000),
            onBackground = Color(0xFFE9E4E2),
            surface = Color(0xFF000000),
            onSurface = Color(0xFFE9E4E2),
            surfaceVariant = Color(0xFF413530),
            onSurfaceVariant = Color(0xFFCEC5C0),
            outline = Color(0xFF938076),
            error = Color(0xFFF2B8B5),
            onError = Color(0xFF601410),
        )

        // **White elements on black**, the operator's own words for this theme: "Make chocolate
        // theme show white elements on black in dark mode". The mirror of its light half, and the
        // only one of the five with no hue at all - which is what lets it be told apart from
        // Brownie, whose palette this used to be.
        CHOCOLATE -> darkColorScheme(
            primary = Color(0xFFFFFFFF),
            onPrimary = Color(0xFF000000),
            primaryContainer = Color(0xFF3A3A3A),
            onPrimaryContainer = Color(0xFFFFFFFF),
            secondary = Color(0xFFE0E0E0),
            onSecondary = Color(0xFF000000),
            secondaryContainer = Color(0xFF2E2E2E),
            onSecondaryContainer = Color(0xFFF5F5F5),
            tertiary = Color(0xFFC4C4C4),
            onTertiary = Color(0xFF000000),
            tertiaryContainer = Color(0xFF262626),
            onTertiaryContainer = Color(0xFFEDEDED),
            background = Color(0xFF000000),
            onBackground = Color(0xFFFFFFFF),
            surface = Color(0xFF000000),
            onSurface = Color(0xFFFFFFFF),
            surfaceVariant = Color(0xFF242424),
            onSurfaceVariant = Color(0xFFCCCCCC),
            outline = Color(0xFF8A8A8A),
            error = Color(0xFFF2B8B5),
            onError = Color(0xFF601410),
        )
    }
}
