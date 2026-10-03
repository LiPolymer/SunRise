package ink.lipoly.app.sunrise

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import ink.lipoly.app.sunrise.composeLegacy.*
import java.util.Locale

@Composable
fun DesktopApp() {
    var settings by remember { mutableStateOf(UiSettings()) }
    val navigation = rememberAppNavigationState()
    SunRiseTheme(settings, dark = settings.isDark(isSystemInDarkTheme())) {
        AppEntry(
            client = null,
            missingPermissions = emptySet(),
            onRequestPermissions = {},
            settings = settings,
            english = settings.language.usesEnglish(Locale.getDefault().language),
            dynamicColorAvailable = false,
            navigation = navigation,
            onSettingsChange = { settings = it },
        )
    }
}
