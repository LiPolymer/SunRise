package ink.lipoly.app.sunrise

import androidx.compose.runtime.Composable
import ink.lipoly.app.sunrise.composeLegacy.*
import ink.lipoly.app.sunrise.headset.HeadsetClient
import ink.lipoly.app.sunrise.catalog.CatalogDocuments

@Composable
internal fun AppEntry(
    client: HeadsetClient?,
    documents: CatalogDocuments,
    missingPermissions: Set<String>,
    onRequestPermissions: () -> Unit,
    settings: UiSettings,
    english: Boolean,
    dynamicColorAvailable: Boolean,
    navigation: AppNavigationState,
    onSettingsChange: (UiSettings) -> Unit,
) {
    AppContent(client, documents, missingPermissions, onRequestPermissions, settings, english, dynamicColorAvailable, navigation, onSettingsChange)
}