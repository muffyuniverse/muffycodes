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
 * | Milk | cool sage green, on a near-white page |
 * | Caramel | warm orange |
 * | Gold | yellow-gold, deliberately the one that stays close to the old palette |
 * | Brownie | deep plum, so the "dark" member of the light set is not another brown |
 * | Chocolate | true brown, and the only one that is |
 *
 * **The error colour is the same in all five.** A red that a theme could change would be a theme
 * that could hide a failure.
 */
enum class AppTheme(
    val displayName: String,
    val swatch: Color,
) {
    MILK("Milk", Color(0xFFCFE3D6)),
    CARAMEL("Caramel", Color(0xFFE08A3C)),
    GOLD("Gold", Color(0xFFE8B923)),
    BROWNIE("Brownie", Color(0xFF8B5FA8)),
    CHOCOLATE("Chocolate", Color(0xFF5A3A24));

    /** The light scheme for this theme. */
    fun lightScheme(): ColorScheme = when (this) {
        MILK -> lightColorScheme(
            primary = Color(0xFF257E52),
            onPrimary = Color(0xFFFCFDFC),
            primaryContainer = Color(0xFFD6F5E6),
            onPrimaryContainer = Color(0xFF09341F),
            secondary = Color(0xFF3C7C77),
            onSecondary = Color(0xFFFCFDFD),
            secondaryContainer = Color(0xFFDAF1EF),
            onSecondaryContainer = Color(0xFF0E2F2D),
            tertiary = Color(0xFF386175),
            onTertiary = Color(0xFFFCFDFD),
            tertiaryContainer = Color(0xFFD3E5EE),
            onTertiaryContainer = Color(0xFF0C2531),
            background = Color(0xFFFAFDFB),
            onBackground = Color(0xFF17261F),
            surface = Color(0xFFFAFDFB),
            onSurface = Color(0xFF17261F),
            surfaceVariant = Color(0xFFDEEDE6),
            onSurfaceVariant = Color(0xFF3D5C4D),
            outline = Color(0xFF699680),
            error = Color(0xFFB3261E),
            onError = Color(0xFFFFFFFF),
        )

        CARAMEL -> lightColorScheme(
            primary = Color(0xFF7E4A25),
            onPrimary = Color(0xFFFDFCFC),
            primaryContainer = Color(0xFFF5E3D6),
            onPrimaryContainer = Color(0xFF341B09),
            secondary = Color(0xFF7C4C3C),
            onSecondary = Color(0xFFFDFCFC),
            secondaryContainer = Color(0xFFF1E0DA),
            onSecondaryContainer = Color(0xFF2F160E),
            tertiary = Color(0xFF756638),
            onTertiary = Color(0xFFFDFDFC),
            tertiaryContainer = Color(0xFFEEE7D3),
            onTertiaryContainer = Color(0xFF31280C),
            background = Color(0xFFFDFBFA),
            onBackground = Color(0xFF261D17),
            surface = Color(0xFFFDFBFA),
            onSurface = Color(0xFF261D17),
            surfaceVariant = Color(0xFFEDE4DE),
            onSurfaceVariant = Color(0xFF5C4A3D),
            outline = Color(0xFF967C69),
            error = Color(0xFFB3261E),
            onError = Color(0xFFFFFFFF),
        )

        GOLD -> lightColorScheme(
            primary = Color(0xFFA08D0D),
            onPrimary = Color(0xFFFDFDFC),
            primaryContainer = Color(0xFFFCF5C5),
            onPrimaryContainer = Color(0xFF3A3303),
            secondary = Color(0xFF796934),
            onSecondary = Color(0xFFFDFDFC),
            secondaryContainer = Color(0xFFEEE8D3),
            onSecondaryContainer = Color(0xFF2F280E),
            tertiary = Color(0xFF547934),
            onTertiary = Color(0xFFFCFDFC),
            tertiaryContainer = Color(0xFFDFEED3),
            onTertiaryContainer = Color(0xFF1D310C),
            background = Color(0xFFFCFBF5),
            onBackground = Color(0xFF2C2911),
            surface = Color(0xFFFCFBF5),
            onSurface = Color(0xFF2C2911),
            surfaceVariant = Color(0xFFEEEAD3),
            onSurfaceVariant = Color(0xFF635D36),
            outline = Color(0xFF9F9556),
            error = Color(0xFFB3261E),
            onError = Color(0xFFFFFFFF),
        )

        BROWNIE -> lightColorScheme(
            primary = Color(0xFF68257E),
            onPrimary = Color(0xFFFDFCFD),
            primaryContainer = Color(0xFFEDD6F5),
            onPrimaryContainer = Color(0xFF290934),
            secondary = Color(0xFF513C7C),
            onSecondary = Color(0xFFFCFCFD),
            secondaryContainer = Color(0xFFE2DAF1),
            onSecondaryContainer = Color(0xFF190E2F),
            tertiary = Color(0xFF753861),
            onTertiary = Color(0xFFFDFCFD),
            tertiaryContainer = Color(0xFFEED3E5),
            onTertiaryContainer = Color(0xFF310C25),
            background = Color(0xFFFCFAFD),
            onBackground = Color(0xFF221726),
            surface = Color(0xFFFCFAFD),
            onSurface = Color(0xFF221726),
            surfaceVariant = Color(0xFFE9DEED),
            onSurfaceVariant = Color(0xFF543D5C),
            outline = Color(0xFF8B6996),
            error = Color(0xFFB3261E),
            onError = Color(0xFFFFFFFF),
        )

        CHOCOLATE -> lightColorScheme(
            primary = Color(0xFF493227),
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
            background = Color(0xFFF9F7F6),
            onBackground = Color(0xFF201713),
            surface = Color(0xFFF9F7F6),
            onSurface = Color(0xFF201713),
            surfaceVariant = Color(0xFFE6DFDB),
            onSurfaceVariant = Color(0xFF4E3E36),
            outline = Color(0xFF846D62),
            error = Color(0xFFB3261E),
            onError = Color(0xFFFFFFFF),
        )
    }

    /** The dark scheme for this theme. */
    fun darkScheme(): ColorScheme = when (this) {
        MILK -> darkColorScheme(
            primary = Color(0xFF90DFB8),
            onPrimary = Color(0xFF0C4529),
            primaryContainer = Color(0xFF26734D),
            onPrimaryContainer = Color(0xFFD6F5E6),
            secondary = Color(0xFF9FD1CC),
            onSecondary = Color(0xFF1C403D),
            secondaryContainer = Color(0xFF366360),
            onSecondaryContainer = Color(0xFFDAF1EF),
            tertiary = Color(0xFF9FC0D1),
            onTertiary = Color(0xFF182E39),
            tertiaryContainer = Color(0xFF324F5D),
            onTertiaryContainer = Color(0xFFDAE9F1),
            background = Color(0xFF0C1712),
            onBackground = Color(0xFFE0EBE6),
            surface = Color(0xFF0C1712),
            onSurface = Color(0xFFE0EBE6),
            surfaceVariant = Color(0xFF395647),
            onSurfaceVariant = Color(0xFFBDD1C7),
            outline = Color(0xFF84A494),
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
            background = Color(0xFF17110C),
            onBackground = Color(0xFFEBE5E0),
            surface = Color(0xFF17110C),
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
            background = Color(0xFF19170B),
            onBackground = Color(0xFFEDEBDE),
            surface = Color(0xFF19170B),
            onSurface = Color(0xFFEDEBDE),
            surfaceVariant = Color(0xFF595436),
            onSurfaceVariant = Color(0xFFD3D0BB),
            outline = Color(0xFFA9A47E),
            error = Color(0xFFF2B8B5),
            onError = Color(0xFF601410),
        )

        BROWNIE -> darkColorScheme(
            primary = Color(0xFFCB90DF),
            onPrimary = Color(0xFF370C45),
            primaryContainer = Color(0xFF602673),
            onPrimaryContainer = Color(0xFFEDD6F5),
            secondary = Color(0xFFAF9FD1),
            onSecondary = Color(0xFF281C40),
            secondaryContainer = Color(0xFF453663),
            onSecondaryContainer = Color(0xFFE2DAF1),
            tertiary = Color(0xFFD19FC0),
            onTertiary = Color(0xFF39182E),
            tertiaryContainer = Color(0xFF5D324F),
            onTertiaryContainer = Color(0xFFF1DAE9),
            background = Color(0xFF150C17),
            onBackground = Color(0xFFE8E0EB),
            surface = Color(0xFF150C17),
            onSurface = Color(0xFFE8E0EB),
            surfaceVariant = Color(0xFF4F3956),
            onSurfaceVariant = Color(0xFFCCBDD1),
            outline = Color(0xFF9C84A4),
            error = Color(0xFFF2B8B5),
            onError = Color(0xFF601410),
        )

        CHOCOLATE -> darkColorScheme(
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
            background = Color(0xFF100C0A),
            onBackground = Color(0xFFE9E4E2),
            surface = Color(0xFF100C0A),
            onSurface = Color(0xFFE9E4E2),
            surfaceVariant = Color(0xFF413530),
            onSurfaceVariant = Color(0xFFCEC5C0),
            outline = Color(0xFF938076),
            error = Color(0xFFF2B8B5),
            onError = Color(0xFF601410),
        )
    }
}
