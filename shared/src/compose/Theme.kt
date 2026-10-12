package ink.lipoly.app.sunrise.compose

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import ink.lipoly.app.sunrise.settings.UiSettings

private class SeedPalette(
    val swatch: Color,
    private val primary: Map<Int, Color>,
    private val secondary: Map<Int, Color>,
    private val tertiary: Map<Int, Color>,
    private val neutral: Map<Int, Color>,
    private val neutralVariant: Map<Int, Color>,
) {
    val light: ColorScheme = scheme(dark = false)
    val dark: ColorScheme = scheme(dark = true)

    private fun scheme(dark: Boolean): ColorScheme {
        fun Map<Int, Color>.tone(lightTone: Int, darkTone: Int = lightTone): Color =
            getValue(if (dark) darkTone else lightTone)

        // Preserve the chosen swatch as the light primary; derive every other role from its HCT hue.
        val primaryColor = if (dark) primary.getValue(80) else swatch
        return (if (dark) darkColorScheme() else expressiveLightColorScheme()).copy(
            primary = primaryColor,
            onPrimary = primary.tone(100, 20),
            primaryContainer = primary.tone(90, 30),
            onPrimaryContainer = primary.tone(30, 90),
            inversePrimary = if (dark) swatch else primary.getValue(80),
            secondary = secondary.tone(40, 80),
            onSecondary = secondary.tone(100, 20),
            secondaryContainer = secondary.tone(90, 30),
            onSecondaryContainer = secondary.tone(30, 90),
            tertiary = tertiary.tone(40, 80),
            onTertiary = tertiary.tone(100, 20),
            tertiaryContainer = tertiary.tone(90, 30),
            onTertiaryContainer = tertiary.tone(30, 90),
            background = neutral.tone(98, 6),
            onBackground = neutral.tone(10, 90),
            surface = neutral.tone(98, 6),
            onSurface = neutral.tone(10, 90),
            surfaceVariant = neutralVariant.tone(90, 30),
            onSurfaceVariant = neutralVariant.tone(30, 80),
            surfaceTint = primaryColor,
            inverseSurface = neutral.tone(20, 90),
            inverseOnSurface = neutral.tone(95, 20),
            outline = neutralVariant.tone(50, 60),
            outlineVariant = neutralVariant.tone(80, 30),
            surfaceBright = neutral.tone(98, 24),
            surfaceDim = neutral.tone(87, 6),
            surfaceContainerLowest = neutral.tone(100, 4),
            surfaceContainerLow = neutral.tone(96, 10),
            surfaceContainer = neutral.tone(94, 12),
            surfaceContainerHigh = neutral.tone(92, 17),
            surfaceContainerHighest = neutral.tone(90, 22),
            primaryFixed = primary.tone(90),
            primaryFixedDim = primary.tone(80),
            onPrimaryFixed = primary.tone(10),
            onPrimaryFixedVariant = primary.tone(30),
            secondaryFixed = secondary.tone(90),
            secondaryFixedDim = secondary.tone(80),
            onSecondaryFixed = secondary.tone(10),
            onSecondaryFixedVariant = secondary.tone(30),
            tertiaryFixed = tertiary.tone(90),
            tertiaryFixedDim = tertiary.tone(80),
            onTertiaryFixed = tertiary.tone(10),
            onTertiaryFixedVariant = tertiary.tone(30),
        )
    }
}

private fun tones(vararg values: Pair<Int, Long>): Map<Int, Color> =
    values.associate { (tone, argb) -> tone to Color(argb) }

// Generated offline with @material/material-color-utilities 0.3.0. Primary retains the seed's
// chroma; the remaining palettes use SchemeTonalSpot. Error roles keep Material's standard red.
private val seedPalettes = listOf(
    SeedPalette(
        swatch = Color(0xFFAD446F),
        primary = tones(
            10 to 0xFF3E001F, 20 to 0xFF630435, 30 to 0xFF81204C, 40 to 0xFF9F3964,
            80 to 0xFFFFB0CA, 90 to 0xFFFFD9E3, 100 to 0xFFFFFFFF,
        ),
        secondary = tones(
            10 to 0xFF2B151D, 20 to 0xFF422931, 30 to 0xFF5A3F48, 40 to 0xFF74565F,
            80 to 0xFFE2BDC7, 90 to 0xFFFFD9E3, 100 to 0xFFFFFFFF,
        ),
        tertiary = tones(
            10 to 0xFF2E1500, 20 to 0xFF48290C, 30 to 0xFF623F20, 40 to 0xFF7D5636,
            80 to 0xFFEFBC95, 90 to 0xFFFFDCC2, 100 to 0xFFFFFFFF,
        ),
        neutral = tones(
            4 to 0xFF140C0E, 6 to 0xFF191114, 10 to 0xFF22191C, 12 to 0xFF261D20,
            17 to 0xFF31282A, 20 to 0xFF372E30, 22 to 0xFF3C3235, 24 to 0xFF403739,
            87 to 0xFFE6D6D9, 90 to 0xFFEFDFE2, 92 to 0xFFF4E4E7, 94 to 0xFFFAEAED,
            95 to 0xFFFDEDF0, 96 to 0xFFFFF0F2, 98 to 0xFFFFF8F8, 100 to 0xFFFFFFFF,
        ),
        neutralVariant = tones(
            30 to 0xFF514347, 50 to 0xFF837377, 60 to 0xFF9E8C90, 80 to 0xFFD5C2C6,
            90 to 0xFFF2DDE2,
        ),
    ),
    SeedPalette(
        swatch = Color(0xFF6750A4),
        primary = tones(
            10 to 0xFF22005D, 20 to 0xFF381E72, 30 to 0xFF4F378A, 40 to 0xFF6750A4,
            80 to 0xFFCFBCFF, 90 to 0xFFE9DDFF, 100 to 0xFFFFFFFF,
        ),
        secondary = tones(
            10 to 0xFF1E192B, 20 to 0xFF332D41, 30 to 0xFF4A4458, 40 to 0xFF625B71,
            80 to 0xFFCBC2DB, 90 to 0xFFE8DEF8, 100 to 0xFFFFFFFF,
        ),
        tertiary = tones(
            10 to 0xFF31101D, 20 to 0xFF4A2532, 30 to 0xFF633B48, 40 to 0xFF7E5260,
            80 to 0xFFEFB8C8, 90 to 0xFFFFD9E3, 100 to 0xFFFFFFFF,
        ),
        neutral = tones(
            4 to 0xFF0F0D13, 6 to 0xFF141218, 10 to 0xFF1D1B20, 12 to 0xFF211F24,
            17 to 0xFF2B292F, 20 to 0xFF322F35, 22 to 0xFF36343A, 24 to 0xFF3B383E,
            87 to 0xFFDED8E0, 90 to 0xFFE6E0E9, 92 to 0xFFECE6EE, 94 to 0xFFF2ECF4,
            95 to 0xFFF5EFF7, 96 to 0xFFF8F2FA, 98 to 0xFFFDF7FF, 100 to 0xFFFFFFFF,
        ),
        neutralVariant = tones(
            30 to 0xFF49454E, 50 to 0xFF7A757F, 60 to 0xFF948F99, 80 to 0xFFCAC4CF,
            90 to 0xFFE7E0EB,
        ),
    ),
    SeedPalette(
        swatch = Color(0xFF16658D),
        primary = tones(
            10 to 0xFF001E2E, 20 to 0xFF00344D, 30 to 0xFF004C6D, 40 to 0xFF15648C,
            80 to 0xFF8DCEFB, 90 to 0xFFC8E6FF, 100 to 0xFFFFFFFF,
        ),
        secondary = tones(
            10 to 0xFF0B1D29, 20 to 0xFF21323E, 30 to 0xFF384956, 40 to 0xFF4F616E,
            80 to 0xFFB7C9D8, 90 to 0xFFD2E5F5, 100 to 0xFFFFFFFF,
        ),
        tertiary = tones(
            10 to 0xFF1F1635, 20 to 0xFF342B4B, 30 to 0xFF4B4163, 40 to 0xFF63597C,
            80 to 0xFFCDC0E9, 90 to 0xFFE9DDFF, 100 to 0xFFFFFFFF,
        ),
        neutral = tones(
            4 to 0xFF0A0F12, 6 to 0xFF101417, 10 to 0xFF181C20, 12 to 0xFF1C2024,
            17 to 0xFF262A2E, 20 to 0xFF2D3135, 22 to 0xFF313539, 24 to 0xFF353A3E,
            87 to 0xFFD7DADF, 90 to 0xFFDFE3E7, 92 to 0xFFE5E8ED, 94 to 0xFFEBEEF3,
            95 to 0xFFEEF1F6, 96 to 0xFFF1F4F9, 98 to 0xFFF6FAFE, 100 to 0xFFFFFFFF,
        ),
        neutralVariant = tones(
            30 to 0xFF41484D, 50 to 0xFF71787E, 60 to 0xFF8B9198, 80 to 0xFFC1C7CE,
            90 to 0xFFDDE3EA,
        ),
    ),
    SeedPalette(
        swatch = Color(0xFF306B4B),
        primary = tones(
            10 to 0xFF002111, 20 to 0xFF003920, 30 to 0xFF125133, 40 to 0xFF2E6949,
            80 to 0xFF96D4AD, 90 to 0xFFB2F1C8, 100 to 0xFFFFFFFF,
        ),
        secondary = tones(
            10 to 0xFF0C1F14, 20 to 0xFF213528, 30 to 0xFF374B3E, 40 to 0xFF4E6355,
            80 to 0xFFB5CCBB, 90 to 0xFFD1E8D6, 100 to 0xFFFFFFFF,
        ),
        tertiary = tones(
            10 to 0xFF001F27, 20 to 0xFF043541, 30 to 0xFF224C58, 40 to 0xFF3B6471,
            80 to 0xFFA3CDDC, 90 to 0xFFBFE9F8, 100 to 0xFFFFFFFF,
        ),
        neutral = tones(
            4 to 0xFF0A0F0C, 6 to 0xFF0F1511, 10 to 0xFF171D19, 12 to 0xFF1B211D,
            17 to 0xFF262B27, 20 to 0xFF2C322D, 22 to 0xFF313632, 24 to 0xFF353B36,
            87 to 0xFFD6DBD5, 90 to 0xFFDFE4DD, 92 to 0xFFE4EAE3, 94 to 0xFFEAEFE8,
            95 to 0xFFEDF2EB, 96 to 0xFFF0F5EE, 98 to 0xFFF6FBF4, 100 to 0xFFFFFFFF,
        ),
        neutralVariant = tones(
            30 to 0xFF404942, 50 to 0xFF717972, 60 to 0xFF8A938B, 80 to 0xFFC0C9C0,
            90 to 0xFFDCE5DC,
        ),
    ),
    SeedPalette(
        swatch = Color(0xFF925A16),
        primary = tones(
            10 to 0xFF2C1600, 20 to 0xFF4A2800, 30 to 0xFF693C00, 40 to 0xFF88520D,
            80 to 0xFFFFB86F, 90 to 0xFFFFDCBD, 100 to 0xFFFFFFFF,
        ),
        secondary = tones(
            10 to 0xFF291806, 20 to 0xFF402C18, 30 to 0xFF59422C, 40 to 0xFF725A42,
            80 to 0xFFE1C1A4, 90 to 0xFFFFDCBE, 100 to 0xFFFFFFFF,
        ),
        tertiary = tones(
            10 to 0xFF161F01, 20 to 0xFF2A3410, 30 to 0xFF404B25, 40 to 0xFF58633A,
            80 to 0xFFBFCC9A, 90 to 0xFFDBE8B4, 100 to 0xFFFFFFFF,
        ),
        neutral = tones(
            4 to 0xFF130D07, 6 to 0xFF19120C, 10 to 0xFF211A14, 12 to 0xFF261E18,
            17 to 0xFF302921, 20 to 0xFF372F28, 22 to 0xFF3C332C, 24 to 0xFF403830,
            87 to 0xFFE6D7CD, 90 to 0xFFEFE0D5, 92 to 0xFFF4E6DB, 94 to 0xFFFAEBE0,
            95 to 0xFFFDEEE3, 96 to 0xFFFFF1E7, 98 to 0xFFFFF8F5, 100 to 0xFFFFFFFF,
        ),
        neutralVariant = tones(
            30 to 0xFF51453A, 50 to 0xFF837468, 60 to 0xFF9D8E81, 80 to 0xFFD5C3B5,
            90 to 0xFFF2DFD1,
        ),
    ),
)

internal val seedSwatches: List<Color> = seedPalettes.map { it.swatch }

@Composable
internal fun SunRiseTheme(
    settings: UiSettings,
    dark: Boolean,
    dynamicScheme: ColorScheme? = null,
    content: @Composable () -> Unit,
) {
    val seed = seedPalettes[settings.seedIndex.coerceIn(seedPalettes.indices)]
    val scheme = dynamicScheme ?: if (dark) seed.dark else seed.light
    val finalScheme = if (dark && settings.amoled) scheme.copy(
        background = Color.Black,
        surface = Color.Black,
        surfaceDim = Color.Black,
        surfaceBright = Color(0xFF242424),
        surfaceContainerLowest = Color.Black,
        surfaceContainer = Color(0xFF101010),
        surfaceContainerLow = Color(0xFF090909),
        surfaceContainerHigh = Color(0xFF1A1A1A),
        surfaceContainerHighest = Color(0xFF242424),
    ) else scheme
    MaterialExpressiveTheme(
        colorScheme = finalScheme,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}
