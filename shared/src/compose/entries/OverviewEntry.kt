package ink.lipoly.app.sunrise.compose.entries

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import ink.lipoly.app.sunrise.catalog.CatalogState
import ink.lipoly.app.sunrise.compose.BatteryCell
import ink.lipoly.app.sunrise.compose.ListItemCard
import ink.lipoly.app.sunrise.composeLegacy.hasReadyGaia
import ink.lipoly.app.sunrise.composeLegacy.shownAncModes
import ink.lipoly.app.sunrise.drop.AncMode
import ink.lipoly.app.sunrise.headset.HeadsetPhase
import ink.lipoly.app.sunrise.headset.HeadsetState
import ink.lipoly.app.sunrise.presentation.HeadsetPresentationState
import ink.lipoly.app.sunrise.presentation.PresentationSession

@Composable
internal fun OverviewEntry(
    headsetState: HeadsetState,
    catalogState: CatalogState,
    navStack: NavBackStack<NavKey>,
    missingPermissions: Set<String>,
    onRequestPermissions: () -> Unit,
    session: PresentationSession,
    headsetPresentationState: HeadsetPresentationState
) {
    val controlState = headsetState.controls
    val ready = headsetState.phase == HeadsetPhase.READY && controlState.hasReadyGaia()
    val usable = ready && headsetPresentationState.working == null && missingPermissions.isEmpty()

    LazyColumn {
        item { // DeviceOverview
            ListItemCard {
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
                    Text(headsetState.device?.name ?: "等待设备", fontSize = 30.sp)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                            .padding(0.dp,4.dp,0.dp,0.dp)
                    ) {
                        BatteryCell(
                            "左耳",
                            controlState.battery.left,
                            Modifier.weight(1f)
                        )
                        BatteryCell(
                            "右耳",
                            controlState.battery.right,
                            Modifier.weight(1f)
                        )
                        if (controlState.battery.case != null) BatteryCell(
                            "充电盒",
                            controlState.battery.case,
                            Modifier.weight(1f)
                        )
                    }
                }
            }
        }
        item {
            ListItemCard {
                Row(
                    modifier = Modifier
                        .padding(16.dp)
                ) {
                    val modes = if (ready) shownAncModes(controlState, false) else emptyList()
                    if (controlState.ancMode == null || controlState.ancMode !in modes) {
                        Text(
                            controlState.ancMode?.display
                                ?: if (ready && modes.isEmpty()) "未检测到可用降噪模式。"
                                else if (ready) "当前模式未知，请刷新重试"
                                else "连接后可用",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (modes.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            modes.forEach { mode ->
                                FilterChip(
                                    selected = controlState.ancMode == mode,
                                    onClick = {
                                        session.headset.runAction("设置降噪", control = true) {
                                            gaia.setAncMode(mode)
                                        }
                                    },
                                    label = { Text(mode.display) },
                                    enabled = usable,
                                )
                            }
                        }
                    }
                }
            }
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

private val AncMode.display: String get() = when (this) {
    AncMode.OFF -> "关闭"
    AncMode.NOISE_CANCELLING -> "降噪"
    AncMode.TRANSPARENCY -> "通透"
    AncMode.WIND -> "抗风噪"
    AncMode.ADAPTIVE -> "自适应"
    AncMode.LIVE -> "Live"
}