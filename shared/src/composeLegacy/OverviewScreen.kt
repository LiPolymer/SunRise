package ink.lipoly.app.sunrise.composeLegacy

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ink.lipoly.app.sunrise.drop.AncMode
import ink.lipoly.app.sunrise.drop.AudioCodec
import ink.lipoly.app.sunrise.drop.GaiaIds
import ink.lipoly.app.sunrise.drop.DropPhase
import ink.lipoly.app.sunrise.drop.DropProtocol
import ink.lipoly.app.sunrise.headset.HeadsetPhase
import ink.lipoly.app.sunrise.headset.HeadsetState
import ink.lipoly.app.sunrise.drop.GainLevel
import ink.lipoly.app.sunrise.drop.HeadTrackingMode

@Composable
internal fun OverviewScreen(
    state: HeadsetState,
    missingPermissions: Set<String>,
    clientAvailable: Boolean,
    english: Boolean,
    showWind: Boolean,
    catalogOnlyDevices: Boolean,
    catalogLoading: Boolean,
    catalogAvailable: Boolean,
    catalogError: String?,
    catalogMatched: Boolean,
    referenceName: String?,
    onCatalogFilterChange: (Boolean) -> Unit,
    onChooseHeadset: () -> Unit,
    onOpenCatalog: () -> Unit,
    confirmedReads: Set<OverviewControl>,
    working: String?,
    codecBlocked: Boolean,
    modifier: Modifier = Modifier,
    onRequestPermissions: () -> Unit,
    onRetry: () -> Unit,
    onAnc: (AncMode) -> Unit,
    onGain: (GainLevel) -> Unit,
    onLed: (Boolean) -> Unit,
    onSpatial: (Boolean) -> Unit,
    onTracking: (HeadTrackingMode) -> Unit,
    onCodec: (AudioCodec, Boolean) -> Unit,
) {
    val controlState = state.controls
    val ready = state.phase == HeadsetPhase.READY && controlState.hasReadyGaia()
    val enabled = ready && working == null && missingPermissions.isEmpty()
    val controls = if (ready) confirmedOverviewControls(controlState, confirmedReads) else emptySet()
    var connectionDetailsExpanded by remember { mutableStateOf(false) }
    LazyColumn(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            OverviewCard(tr(english, "设备目录", "Device catalogue")) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(tr(english, "仅显示目录设备", "Only catalogue devices"), Modifier.weight(1f))
                    Switch(checked = catalogOnlyDevices, onCheckedChange = onCatalogFilterChange)
                }
                Text(
                    when {
                        catalogLoading -> tr(english, "正在加载本地目录，暂不自动选择设备。", "Loading local catalogue; automatic selection is paused.")
                        !catalogAvailable -> tr(english, "数据库不可用，可关闭筛选显示全部设备，或在设置中导入／拉取。", "Database unavailable. Disable filtering to show all devices, or import/pull in Settings.")
                        state.device == null -> tr(english, "名称匹配不代表协议支持；控制能力仍由设备探测决定。", "Name matching does not imply protocol support; controls still depend on device probing.")
                        catalogMatched -> tr(english, "当前设备：目录匹配", "Current device: catalogue match")
                        else -> tr(english, "当前设备：未收录名称", "Current device: unlisted name")
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (!catalogAvailable) catalogError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                referenceName?.let { Text(tr(english, "参考型号：$it", "Reference model: $it")) }
                OutlinedButton(onClick = onChooseHeadset,
                    enabled = clientAvailable && missingPermissions.isEmpty() && working == null && !catalogLoading) {
                    Text(tr(english, "选择耳机", "Choose a headset"))
                }
                OutlinedButton(onClick = onOpenCatalog, enabled = catalogAvailable) {
                    Text(tr(english, "浏览目录", "Browse catalog"))
                }
                Text(tr(english, "筛选只影响下一次自动选择，不会断开当前连接。", "Filtering affects the next automatic selection; it does not disconnect the current headset."),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Surface(
                        color = if (state.phase == HeadsetPhase.READY) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                        shape = MaterialTheme.shapes.large,
                    ) {
                        Text(
                            state.phase.display(english),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (state.phase == HeadsetPhase.READY) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Text(
                        state.device?.name?.takeIf { it.isNotBlank() }
                            ?: state.device?.address
                            ?: tr(english, "等待耳机", "Waiting for headset"),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    if (!clientAvailable) {
                        Text(tr(english, "当前平台不支持蓝牙控制", "Bluetooth control is unavailable on this platform"))
                    } else if (missingPermissions.isNotEmpty()) {
                        Text(tr(english, "需要蓝牙权限才能发现并连接耳机。", "Bluetooth permission is required to find and connect a headset."))
                        Button(onClick = onRequestPermissions) { Text(tr(english, "授予权限", "Grant permission")) }
                    } else if (state.phase != HeadsetPhase.READY) {
                        Text(
                            when (state.phase) {
                                HeadsetPhase.SELECTION_REQUIRED ->
                                    tr(english, "发现多台已连接的音频设备。选择你的耳机以继续连接。", "Multiple connected audio devices were found. Choose your headset to continue.")
                                HeadsetPhase.DISCOVERING, HeadsetPhase.CONNECTING, HeadsetPhase.PROBING, HeadsetPhase.RECONNECTING ->
                                    tr(english, "正在寻找耳机并检查连接，请稍候。", "Finding your headset and checking the connection…")
                                else ->
                                    tr(english, "尚未连接耳机。请先在系统蓝牙设置中连接耳机，然后重试。", "No headset connected. Connect it in your device's Bluetooth settings, then retry.")
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (state.phase == HeadsetPhase.IDLE || state.phase == HeadsetPhase.ERROR) {
                            OutlinedButton(onClick = onRetry, enabled = working == null) {
                                Text(tr(english, "重新连接", "Reconnect"))
                            }
                        }
                    }
                    state.error?.let { Text(it.message ?: "", color = MaterialTheme.colorScheme.error) }
                }
            }
        }

        item {
            OverviewCard(tr(english, "电量", "Battery")) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    BatteryCell(tr(english, "左耳", "Left"), controlState.battery.left, Modifier.weight(1f))
                    BatteryCell(tr(english, "右耳", "Right"), controlState.battery.right, Modifier.weight(1f))
                    if (controlState.battery.case != null) BatteryCell(tr(english, "充电盒", "Case"), controlState.battery.case, Modifier.weight(1f))
                }
            }
        }

        item {
            OverviewCard(tr(english, "降噪模式", "Noise control")) {
                val modes = if (ready) shownAncModes(controlState, showWind) else emptyList()
                if (controlState.ancMode == null || controlState.ancMode !in modes) {
                    Text(
                        controlState.ancMode?.display(english)
                            ?: if (ready && modes.isEmpty()) tr(english, "未检测到可用降噪模式。", "No available noise-control modes were detected.")
                            else if (ready) tr(english, "当前模式未知，请刷新重试", "Current mode unknown; refresh to retry")
                            else tr(english, "连接后可用", "Available after connecting"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (modes.isNotEmpty()) {
                    ChipRow {
                        modes.forEach { mode ->
                            FilterChip(
                                selected = controlState.ancMode == mode,
                                onClick = { onAnc(mode) },
                                label = { Text(mode.display(english)) },
                                enabled = enabled,
                            )
                        }
                    }
                }
            }
        }
        item {
            OverviewCard(tr(english, "音频编码", "Audio codecs")) {
                Text(
                    tr(english, "这些开关控制耳机端编码选项；实际音频编码由系统协商。",
                        "These switches control headset codec options; the system negotiates the actual audio codec."),
                    style = MaterialTheme.typography.bodySmall,
                )
                val available = ready && (GaiaIds.CODEC_TYPE in controlState.capabilities.gaiaFeatures ||
                    !controlState.capabilities.complete)
                if (!available) Text(tr(english, "当前会话不支持音频编码控制", "Codec control unavailable in this session"))
                AudioCodec.entries.forEach { codec ->
                    val actual = controlState.codecStates[codec]
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(codec.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                actual?.let { if (it) tr(english, "开启", "On") else tr(english, "关闭", "Off") }
                                    ?: tr(english, "未知", "Unknown"),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (actual != null) Switch(
                            checked = actual,
                            onCheckedChange = { onCodec(codec, it) },
                            enabled = available && enabled && !codecBlocked,
                        )
                    }
                }
                Text(
                    tr(english, "LHDC 支持取决于耳机型号和固件。", "LHDC support depends on the headset model and firmware."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (controls.isNotEmpty()) {
            item {
                OverviewCard(tr(english, "扩展控制", "More controls")) {
                    if (OverviewControl.GAIN in controls) {
                        Text(tr(english, "增益", "Gain"), style = MaterialTheme.typography.titleSmall)
                        if (controlState.gain == null) UnknownValue(english)
                        ChipRow {
                            GainLevel.entries.forEach { gain ->
                                FilterChip(
                                    selected = controlState.gain == gain,
                                    onClick = { onGain(gain) },
                                    label = { Text(gain.display(english)) },
                                    enabled = enabled && controlState.gain != null,
                                )
                            }
                        }
                    }
                    if (OverviewControl.SPATIAL in controls) {
                        ControlSwitch(
                            tr(english, "空间音频", "Spatial audio"), controlState.spatialOn,
                            enabled && controlState.spatialOn != null, english, onSpatial,
                        )
                    }
                    if (OverviewControl.HEAD_TRACKING in controls) {
                        Text(tr(english, "头部追踪", "Head tracking"), style = MaterialTheme.typography.titleSmall)
                        if (controlState.headTracking == null) UnknownValue(english)
                        ChipRow {
                            HeadTrackingMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = controlState.headTracking == mode,
                                    onClick = { onTracking(mode) },
                                    label = { Text(mode.display(english)) },
                                    enabled = enabled && controlState.spatialOn == true && controlState.headTracking != null,
                                )
                            }
                        }
                    }
                    if (OverviewControl.LED in controls) {
                        ControlSwitch("LED", controlState.ledOn, enabled && controlState.ledOn != null, english, onLed)
                    }
                }
            }
        }
        item {
            Card(
                onClick = { connectionDetailsExpanded = !connectionDetailsExpanded },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(tr(english, "连接详情", "Connection details"), style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (connectionDetailsExpanded) tr(english, "收起", "Hide") else tr(english, "展开", "Show"),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    if (connectionDetailsExpanded) {
                        InfoLine(tr(english, "地址", "Address"), state.device?.address ?: "—")
                        InfoLine(
                            tr(english, "控制协议", "Control protocol"),
                            controlState.protocols.joinToString(" · ") { it.display() }.ifEmpty { "—" },
                        )
                        if (!controlState.capabilities.complete &&
                            state.phase == HeadsetPhase.READY && controlState.phase == DropPhase.READY
                        ) {
                            Text(
                                tr(english, "部分能力尚未确认；可在高级诊断中测试。", "Some capabilities are unconfirmed; test them in diagnostics."),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
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
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
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
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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

private fun HeadsetPhase.display(english: Boolean): String = when (this) {
    HeadsetPhase.IDLE -> tr(english, "等待连接", "Waiting")
    HeadsetPhase.DISCOVERING -> tr(english, "发现设备中", "Finding devices")
    HeadsetPhase.CONNECTING -> tr(english, "连接中", "Connecting")
    HeadsetPhase.PROBING -> tr(english, "检测能力中", "Checking capabilities")
    HeadsetPhase.READY -> tr(english, "已连接", "Connected")
    HeadsetPhase.RECONNECTING -> tr(english, "重新连接中", "Reconnecting")
    HeadsetPhase.SELECTION_REQUIRED -> tr(english, "请选择设备", "Choose a device")
    HeadsetPhase.ERROR -> tr(english, "连接错误", "Connection error")
}

private fun DropProtocol.display(): String = when (this) {
    DropProtocol.GAIA_BLE -> "GAIA BLE"
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
