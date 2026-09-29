package ink.lipoly.app.sunrise

import androidx.compose.runtime.Composable
import ink.lipoly.app.sunrise.compose.AppContent
import ink.lipoly.app.sunrise.compose.AppNavigationState
import ink.lipoly.app.sunrise.compose.UiSettings
import ink.lipoly.app.sunrise.drop.DropClient

@Composable
internal fun AppEntry(
    client: DropClient?,
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