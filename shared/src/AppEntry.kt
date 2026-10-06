package ink.lipoly.app.sunrise

import androidx.compose.runtime.Composable
import ink.lipoly.app.sunrise.compose.AppContent
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
    onSettingsChange: (UiSettings) -> Unit,
) {
    AppContent(
        catalog = catalog,
        client = client,
        session = session,
        missingPermissions = missingPermissions,
        onRequestPermissions = onRequestPermissions,
        settings = settings,
        english = english,
        dynamicColorAvailable = dynamicColorAvailable,
        onSettingsChange = onSettingsChange,
    )
}