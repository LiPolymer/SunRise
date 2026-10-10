package ink.lipoly.app.sunrise.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import ink.lipoly.app.sunrise.catalog.Catalog
import ink.lipoly.app.sunrise.compose.OurNavStack.activeNode
import ink.lipoly.app.sunrise.compose.OurNavStack.back
import ink.lipoly.app.sunrise.compose.OurNavStack.createNavigationBarItems
import ink.lipoly.app.sunrise.compose.entries.OverviewEntry
import ink.lipoly.app.sunrise.headset.HeadsetClient
import ink.lipoly.app.sunrise.headset.HeadsetState
import ink.lipoly.app.sunrise.presentation.PresentationSession
import ink.lipoly.app.sunrise.settings.UiSettings

@Composable
internal fun AppContent(
    catalog: Catalog,
    client: HeadsetClient?,
    session: PresentationSession,
    missingPermissions: Set<String>,
    onRequestPermissions: () -> Unit,
    settings: UiSettings,
    dynamicColorAvailable: Boolean,
    onSettingsChange: (UiSettings) -> Unit,
) {
    val headsetState = client?.state?.collectAsState()?.value ?: HeadsetState()
    val headsetPresentationState by session.headset.state.collectAsState()
    val catalogState by catalog.state.collectAsState()
    val eqEditor = rememberParamEqEditor(client, headsetState, session.eq, missingPermissions)
    val eqState = eqEditor?.state?.collectAsState()?.value

    val navStack = OurNavStack.rememberIt()

    Surface(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            bottomBar = {
                NavigationBar(windowInsets = NavigationBarDefaults.windowInsets) {
                    OurNavStack.navigableNodes.createNavigationBarItems(
                        navStack.activeNode
                    ) { navStack.add(it) }
                }
            }
        ) {
            NavDisplay(
                backStack = navStack,
                onBack = { navStack.back() },
                entryProvider = entryProvider {
                    entry<OurNavStack.Route.Overview> {
                        OverviewEntry(
                            headsetState,
                            catalogState,
                            navStack,
                            missingPermissions,
                            onRequestPermissions,
                            session,
                            headsetPresentationState
                        )
                    }
                    entry<OurNavStack.Route.Equalizer> {
                        Text("Eq")
                    }
                    entry<OurNavStack.Route.Settings> {
                        Text("Set")
                    }
                }
            )
        }
    }
}