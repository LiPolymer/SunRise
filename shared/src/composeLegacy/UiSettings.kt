package ink.lipoly.app.sunrise.composeLegacy

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import ink.lipoly.app.sunrise.settings.UiSettings

internal fun tr(english: Boolean, chinese: String, englishText: String): String =
    if (english) englishText else chinese

private data class SeedPalette(val light: Color, val dark: Color, val container: Color)

private val seedPalettes = listOf(
    SeedPalette(Color(0xFFAD446F), Color(0xFFFFB0CF), Color(0xFFFFD8E6)),
    SeedPalette(Color(0xFF6750A4), Color(0xFFD0BCFF), Color(0xFFEADDFF)),
    SeedPalette(Color(0xFF16658D), Color(0xFF9BD2F5), Color(0xFFD1EBFF)),
    SeedPalette(Color(0xFF306B4B), Color(0xFF9BD9AE), Color(0xFFC4F2D1)),
    SeedPalette(Color(0xFF925A16), Color(0xFFFFC77B), Color(0xFFFFDDB1)),
)

internal val seedSwatches: List<Color> = seedPalettes.map { it.light }

@Composable
internal fun SunRiseTheme(
    settings: UiSettings,
    dark: Boolean,
    dynamicScheme: ColorScheme? = null,
    content: @Composable () -> Unit,
) {
    val seed = seedPalettes[settings.seedIndex.coerceIn(seedPalettes.indices)]
    val scheme = dynamicScheme ?: if (dark) {
        darkColorScheme(
            primary = seed.dark,
            onPrimary = Color(0xFF1E1B20),
            primaryContainer = seed.light,
            onPrimaryContainer = Color.White,
        )
    } else {
        lightColorScheme(
            primary = seed.light,
            onPrimary = Color.White,
            primaryContainer = seed.container,
            onPrimaryContainer = Color(0xFF2D2025),
        )
    }
    val finalScheme = if (dark && settings.amoled) scheme.copy(
        background = Color.Black,
        surface = Color.Black,
        surfaceContainer = Color(0xFF101010),
        surfaceContainerLow = Color(0xFF090909),
        surfaceContainerHigh = Color(0xFF1A1A1A),
    ) else scheme
    MaterialTheme(colorScheme = finalScheme, content = content)
}
