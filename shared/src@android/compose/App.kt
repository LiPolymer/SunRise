package ink.lipoly.app.sunrise.compose

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import ink.lipoly.app.sunrise.AppEntry
import ink.lipoly.app.sunrise.headset.HeadsetClient

@Composable
@Preview
fun App(
    client: HeadsetClient? = null,
    missingPermissions: Set<String> = emptySet(),
    onRequestPermissions: () -> Unit = {},
) {
    val context = LocalContext.current
    val settingsStore = remember(context) { UiSettingsStore(context) }
    val settings = settingsStore.current
    val navigation = rememberAppNavigationState()

    BackHandler(navigation.diagnostics) { navigation.diagnostics = false }
    AndroidSunRiseTheme(settings) {
        AppEntry(
            client = client,
            missingPermissions = missingPermissions,
            onRequestPermissions = onRequestPermissions,
            settings = settings,
            english = settings.english(),
            dynamicColorAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
            navigation = navigation,
            onSettingsChange = settingsStore::update,
        )
    }
}