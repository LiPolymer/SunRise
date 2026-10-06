package ink.lipoly.app.sunrise

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import ink.lipoly.app.sunrise.composeLegacy.SunRiseTheme
import ink.lipoly.app.sunrise.settings.ThemeMode
import ink.lipoly.app.sunrise.settings.UiLanguage
import ink.lipoly.app.sunrise.settings.UiSettings
import ink.lipoly.app.sunrise.settings.UiSettingsStore
import ink.lipoly.app.sunrise.settings.decodeReferenceProducts
import ink.lipoly.app.sunrise.settings.encodeReferenceProducts
import ink.lipoly.app.sunrise.settings.isDark
import ink.lipoly.app.sunrise.settings.usesEnglish
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class AndroidUiSettingsStore(private val preferences: SharedPreferences) : UiSettingsStore {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences("sunrise_ui", Context.MODE_PRIVATE))

    private val mutableState = MutableStateFlow(
        UiSettings(
            themeMode = enumAt(preferences.getInt("theme_mode", 0), ThemeMode.entries),
            dynamicColor = preferences.getBoolean("dynamic_color", true),
            amoled = preferences.getBoolean("amoled", false),
            seedIndex = preferences.getInt("seed", 0).coerceIn(0, 4),
            language = enumAt(preferences.getInt("language", 0), UiLanguage.entries),
            showWind = preferences.getBoolean("show_wind", true),
            catalogOnlyDevices = preferences.getBoolean("catalog_only_devices", true),
            showReferenceResponse = preferences.getBoolean("show_reference_response", true),
            includeResponsePreGain = preferences.getBoolean("include_response_pregain", true),
            referenceProductByAddress = decodeReferenceProducts(preferences.getString("reference_products", null)),
            targetProductUuid = preferences.getString("target_product_uuid", null),
            showTargetResponse = preferences.getBoolean("show_target_response", true),
        )
    )
    override val state: StateFlow<UiSettings> = mutableState.asStateFlow()

    override fun update(next: UiSettings) {
        mutableState.value = next
        preferences.edit {
            putInt("theme_mode", next.themeMode.ordinal)
            putBoolean("dynamic_color", next.dynamicColor)
            putBoolean("amoled", next.amoled)
            putInt("seed", next.seedIndex)
            putInt("language", next.language.ordinal)
            putBoolean("show_wind", next.showWind)
            putBoolean("catalog_only_devices", next.catalogOnlyDevices)
            putBoolean("show_reference_response", next.showReferenceResponse)
            putBoolean("include_response_pregain", next.includeResponsePreGain)
            putString("reference_products", encodeReferenceProducts(next.referenceProductByAddress))
            putBoolean("show_target_response", next.showTargetResponse)
            if (next.targetProductUuid == null) remove("target_product_uuid")
            else putString("target_product_uuid", next.targetProductUuid)
        }
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
