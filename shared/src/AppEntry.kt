package ink.lipoly.app.sunrise

import androidx.compose.runtime.Composable
import ink.lipoly.app.sunrise.composeLegacy.*
import ink.lipoly.app.sunrise.headset.HeadsetClient

@Composable
internal fun AppEntry(
    client: HeadsetClient?,
    missingPermissions: Set<String>,
    onRequestPermissions: () -> Unit,
    settings: UiSettings,
    english: Boolean,
    dynamicColorAvailable: Boolean,
    navigation: AppNavigationState,
    onSettingsChange: (UiSettings) -> Unit,
) {
    AppContent(client, missingPermissions, onRequestPermissions, settings, english, dynamicColorAvailable, navigation, onSettingsChange)
}