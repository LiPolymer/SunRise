package ink.lipoly.app.sunrise.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import ink.lipoly.app.sunrise.catalog.Catalog
import ink.lipoly.app.sunrise.compose.OurNavStack.activeNode
import ink.lipoly.app.sunrise.compose.OurNavStack.back
import ink.lipoly.app.sunrise.compose.OurNavStack.createNavigationBarItems
import ink.lipoly.app.sunrise.headset.HeadsetClient
import ink.lipoly.app.sunrise.headset.HeadsetPhase
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
    val catalogState by catalog.state.collectAsState()
    val eqEditor = rememberParamEqEditor(client, headsetState, session.eq, missingPermissions)
    val eqState = eqEditor?.state?.collectAsState()?.value

    val navStack = OurNavStack.rememberIt()

    Surface(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            bottomBar = {
                NavigationBar(windowInsets = NavigationBarDefaults.windowInsets) {
                    OurNavStack.navigatableNodes.createNavigationBarItems(
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
                        Column {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                ),
                                modifier = Modifier
                                    .padding(10.dp)
                                    .fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier
                                        .padding(16.dp)
                                ) {
                                    Surface(
                                        color = if (headsetState.phase == HeadsetPhase.READY) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                                        shape = MaterialTheme.shapes.large,
                                    ) {
                                        Text(
                                            headsetState.phase.display,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (headsetState.phase == HeadsetPhase.READY)
                                                MaterialTheme.colorScheme.onPrimary else
                                                MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                    Text("设备", fontSize = 30.sp)
                                }
                            }
                        }
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

private val HeadsetPhase.display: String get() = when (this) {
    HeadsetPhase.IDLE -> "等待连接"
    HeadsetPhase.DISCOVERING -> "发现设备中"
    HeadsetPhase.CONNECTING -> "连接中"
    HeadsetPhase.PROBING -> "检测能力中"
    HeadsetPhase.READY -> "已连接"
    HeadsetPhase.RECONNECTING -> "重新连接中"
    HeadsetPhase.SELECTION_REQUIRED -> "请选择设备"
    HeadsetPhase.ERROR -> "连接错误"
}
