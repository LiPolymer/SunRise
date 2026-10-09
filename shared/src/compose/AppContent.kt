package ink.lipoly.app.sunrise.compose

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ink.lipoly.app.sunrise.catalog.Catalog
import ink.lipoly.app.sunrise.headset.HeadsetClient
import ink.lipoly.app.sunrise.headset.HeadsetPhase
import ink.lipoly.app.sunrise.headset.HeadsetState
import ink.lipoly.app.sunrise.icons.ImportedIcons
import ink.lipoly.app.sunrise.presentation.PresentationSession
import ink.lipoly.app.sunrise.settings.UiSettings

/**
 * Rewrite the shared UI here. The host owns these services; do not close them from a page.
 * session.headset: connection/actions; session.catalog: import/export/pull; session.eq: editor owner.
 * Read service/editor StateFlows with collectAsState, and persist settings with onSettingsChange.
 */
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

    Surface(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            bottomBar = {
                BottomAppBar(
                    actions = {
                        Row {
                            IconButton(onClick = {

                            }) {
                                Icon(
                                    imageVector = ImportedIcons.Tune,
                                    contentDescription = "返回"
                                )
                            }
                        }
                    },
                    floatingActionButton = {
                        FloatingActionButton(
                            onClick = {},
                            containerColor = BottomAppBarDefaults.bottomAppBarFabColor,
                            elevation = FloatingActionButtonDefaults.bottomAppBarFabElevation()) {
                            Icon(
                                imageVector = Icons.Outlined.Settings,
                                contentDescription = "设置"
                            )
                        }
                    }
                )
            }
        ) { Column {
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
        } }
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
