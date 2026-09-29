package ink.lipoly.app.sunrise.compose

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

internal class UiSettingsStore(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences("sunrise_ui", Context.MODE_PRIVATE))

    var current by mutableStateOf(
        UiSettings(
            themeMode = enumAt(preferences.getInt("theme_mode", 0), ThemeMode.entries),
            dynamicColor = preferences.getBoolean("dynamic_color", true),
            amoled = preferences.getBoolean("amoled", false),
            seedIndex = preferences.getInt("seed", 0).coerceIn(0, 4),
            language = enumAt(preferences.getInt("language", 0), UiLanguage.entries),
            showWind = preferences.getBoolean("show_wind", true),
        )
    )
        private set

    fun update(next: UiSettings) {
        current = next
        preferences.edit()
            .putInt("theme_mode", next.themeMode.ordinal)
            .putBoolean("dynamic_color", next.dynamicColor)
            .putBoolean("amoled", next.amoled)
            .putInt("seed", next.seedIndex)
            .putInt("language", next.language.ordinal)
            .putBoolean("show_wind", next.showWind)
            .apply()
    }
}

private fun <T> enumAt(index: Int, values: List<T>): T = values.getOrElse(index) { values.first() }

@Composable
internal fun UiSettings.english(): Boolean {
    val configuration = LocalConfiguration.current
    return language.usesEnglish(configuration.locales[0]?.language ?: "en")
}

@Composable
internal fun AndroidSunRiseTheme(settings: UiSettings, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = settings.isDark(isSystemInDarkTheme())
    val dynamicScheme = if (settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else null
    SunRiseTheme(settings, dark, dynamicScheme, content)
}
