package ink.lipoly.app.sunrise.compose

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ink.lipoly.app.sunrise.drop.AncMode
import ink.lipoly.app.sunrise.drop.DropPhase
import ink.lipoly.app.sunrise.drop.DropProtocol
import ink.lipoly.app.sunrise.drop.DropState
import ink.lipoly.app.sunrise.drop.GainLevel
import ink.lipoly.app.sunrise.drop.HeadTrackingMode

@Composable
internal fun OverviewScreen(
    state: DropState,
    missingPermissions: Set<String>,
    clientAvailable: Boolean,
    english: Boolean,
    showWind: Boolean,
    confirmedReads: Set<OverviewControl>,
    working: String?,
    modifier: Modifier = Modifier,
    onRequestPermissions: () -> Unit,
    onRetry: () -> Unit,
    onAnc: (AncMode) -> Unit,
    onGain: (GainLevel) -> Unit,
    onLed: (Boolean) -> Unit,
    onSpatial: (Boolean) -> Unit,
    onTracking: (HeadTrackingMode) -> Unit,
) {
    val ready = state.hasReadyGaia()
    val enabled = ready && working == null && missingPermissions.isEmpty()
    val controls = confirmedOverviewControls(state, confirmedReads)
    LazyColumn(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Surface(
                        color = if (state.phase == DropPhase.READY) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                        shape = MaterialTheme.shapes.large,
                    ) {
                        Text(
                            state.phase.display(english),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (state.phase == DropPhase.READY) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Text(
                        state.device?.name ?: tr(english, "等待耳机", "Waiting for headset"),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    if (!clientAvailable) {
                        Text(tr(english, "当前平台不支持蓝牙控制", "Bluetooth control is unavailable on this platform"))
                    } else if (missingPermissions.isNotEmpty()) {
                        Text(tr(english, "需要蓝牙权限才能发现并连接耳机。", "Bluetooth permission is required to find and connect a headset."))
                        Button(onClick = onRequestPermissions) { Text(tr(english, "授予权限", "Grant permission")) }
                    } else if (state.phase != DropPhase.READY) {
                        Text(
                            if (state.phase == DropPhase.SELECTION_REQUIRED)
                                tr(english, "发现多台音频设备，请选择耳机。", "Multiple audio devices found. Choose your headset.")
                            else tr(english, "打开耳机并将其连接到系统蓝牙。", "Turn on the headset and connect it in Bluetooth settings."),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedButton(onClick = onRetry, enabled = working == null) {
                            Text(tr(english, "重新连接", "Reconnect"))
                        }
                    }
                    state.error?.let { Text(it.message ?: "", color = MaterialTheme.colorScheme.error) }
                }
            }
        }

        item {
            OverviewCard(tr(english, "电量", "Battery")) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    BatteryCell(tr(english, "左耳", "Left"), state.battery.left, Modifier.weight(1f))
                    BatteryCell(tr(english, "右耳", "Right"), state.battery.right, Modifier.weight(1f))
                    if (state.battery.case != null) BatteryCell(tr(english, "充电盒", "Case"), state.battery.case, Modifier.weight(1f))
                }
            }
        }

        item {
            OverviewCard(tr(english, "降噪模式", "Noise control")) {
                Text(
                    state.ancMode?.display(english)
                        ?: if (ready) tr(english, "当前模式未知，请刷新重试", "Current mode unknown; refresh to retry")
                        else tr(english, "连接后可用", "Available after connecting"),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (state.ancMode == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                )
                val modes = shownAncModes(state, showWind)
                if (modes.isNotEmpty()) {
                    ChipRow {
                        modes.forEach { mode ->
                            FilterChip(
                                selected = state.ancMode == mode,
                                onClick = { onAnc(mode) },
                                label = { Text(mode.display(english)) },
                                enabled = enabled,
                            )
                        }
                    }
                } else if (ready) {
                    Text(
                        tr(english, "未检测到可用降噪模式。", "No available noise-control modes were detected."),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (controls.isNotEmpty()) {
            item {
                OverviewCard(tr(english, "扩展控制", "More controls")) {
                    if (OverviewControl.GAIN in controls) {
                        Text(tr(english, "增益", "Gain"), style = MaterialTheme.typography.titleSmall)
                        if (state.gain == null) UnknownValue(english)
                        ChipRow {
                            GainLevel.entries.forEach { gain ->
                                FilterChip(
                                    selected = state.gain == gain,
                                    onClick = { onGain(gain) },
                                    label = { Text(gain.display(english)) },
                                    enabled = enabled && state.gain != null,
                                )
                            }
                        }
                    }
                    if (OverviewControl.SPATIAL in controls) {
                        ControlSwitch(
                            tr(english, "空间音频", "Spatial audio"), state.spatialOn,
                            enabled && state.spatialOn != null, english, onSpatial,
                        )
                    }
                    if (OverviewControl.HEAD_TRACKING in controls) {
                        Text(tr(english, "头部追踪", "Head tracking"), style = MaterialTheme.typography.titleSmall)
                        if (state.headTracking == null) UnknownValue(english)
                        ChipRow {
                            HeadTrackingMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = state.headTracking == mode,
                                    onClick = { onTracking(mode) },
                                    label = { Text(mode.display(english)) },
                                    enabled = enabled && state.spatialOn == true && state.headTracking != null,
                                )
                            }
                        }
                    }
                    if (OverviewControl.LED in controls) {
                        ControlSwitch("LED", state.ledOn, enabled && state.ledOn != null, english, onLed)
                    }
                }
            }
        }
        item {
            OverviewCard(tr(english, "连接详情", "Connection details")) {
                InfoLine(tr(english, "地址", "Address"), state.device?.address ?: "—")
                InfoLine(
                    tr(english, "控制协议", "Control protocol"),
                    state.protocols.joinToString(" · ") { it.display() }.ifEmpty { "—" },
                )
                if (!state.capabilities.complete && state.phase == DropPhase.READY) {
                    Text(
                        tr(english, "部分能力尚未确认；可在高级诊断中测试。", "Some capabilities are unconfirmed; test them in diagnostics."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            Text(
                tr(english, "控制结果以耳机读回状态为准。", "Controls reflect the state read back from the headset."),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OverviewCard(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End)
    }
}

@Composable
private fun BatteryCell(label: String, percent: Int?, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(percent?.let { "$it%" } ?: "—", style = MaterialTheme.typography.titleLarge)
            if (percent != null) {
                LinearProgressIndicator(
                    progress = { percent.coerceIn(0, 100) / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
private fun UnknownValue(english: Boolean) {
    Text(tr(english, "状态尚未读取，可点右上角刷新。", "State not read yet; tap Refresh."),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ControlSwitch(
    label: String,
    value: Boolean?,
    enabled: Boolean,
    english: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(
                value?.let { if (it) tr(english, "开启", "On") else tr(english, "关闭", "Off") }
                    ?: tr(english, "未读取", "Not read"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = value == true, onCheckedChange = onChange, enabled = enabled)
    }
}

private fun DropPhase.display(english: Boolean): String = when (this) {
    DropPhase.IDLE -> tr(english, "等待连接", "Waiting")
    DropPhase.DISCOVERING -> tr(english, "发现设备中", "Finding devices")
    DropPhase.CONNECTING -> tr(english, "连接中", "Connecting")
    DropPhase.PROBING -> tr(english, "检测能力中", "Checking capabilities")
    DropPhase.READY -> tr(english, "已连接", "Connected")
    DropPhase.RECONNECTING -> tr(english, "重新连接中", "Reconnecting")
    DropPhase.SELECTION_REQUIRED -> tr(english, "请选择设备", "Choose a device")
    DropPhase.ERROR -> tr(english, "连接错误", "Connection error")
}

private fun DropProtocol.display(): String = when (this) {
    DropProtocol.GAIA_BLE -> "GAIA BLE"
    DropProtocol.GAIA_RFCOMM -> "GAIA RFCOMM"
    DropProtocol.SOURCE_9ECA -> "9ECA"
}

internal fun AncMode.display(english: Boolean): String = when (this) {
    AncMode.OFF -> tr(english, "关闭", "Off")
    AncMode.NOISE_CANCELLING -> tr(english, "降噪", "Noise cancelling")
    AncMode.TRANSPARENCY -> tr(english, "通透", "Transparency")
    AncMode.WIND -> tr(english, "抗风噪", "Wind")
    AncMode.ADAPTIVE -> tr(english, "自适应", "Adaptive")
    AncMode.LIVE -> "Live"
}

private fun GainLevel.display(english: Boolean): String = when (this) {
    GainLevel.LOW -> tr(english, "低", "Low")
    GainLevel.MEDIUM -> tr(english, "中", "Medium")
    GainLevel.HIGH -> tr(english, "高", "High")
}

private fun HeadTrackingMode.display(english: Boolean): String = when (this) {
    HeadTrackingMode.OFF -> tr(english, "关闭", "Off")
    HeadTrackingMode.THIRTY_DEGREES -> "30°"
    HeadTrackingMode.SURROUND -> tr(english, "环绕", "Surround")
}
