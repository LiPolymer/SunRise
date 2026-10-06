package ink.lipoly.app.sunrise

import androidx.compose.runtime.Composable
import ink.lipoly.app.sunrise.composeLegacy.*
import ink.lipoly.app.sunrise.headset.HeadsetClient
import ink.lipoly.app.sunrise.presentation.PresentationSession
import ink.lipoly.app.sunrise.catalog.Catalog
import ink.lipoly.app.sunrise.settings.UiSettings

@Composable
internal fun AppEntry(
    catalog: Catalog,
    client: HeadsetClient?,
    session: PresentationSession,
    missingPermissions: Set<String>,
    onRequestPermissions: () -> Unit,
    settings: UiSettings,
    english: Boolean,
    dynamicColorAvailable: Boolean,
    navigation: AppNavigationState,
    onSettingsChange: (UiSettings) -> Unit,
) {
    AppContent(catalog, client, session, missingPermissions, onRequestPermissions, settings, english, dynamicColorAvailable, navigation, onSettingsChange)
}