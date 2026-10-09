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
            OverviewCard("设备目录") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("仅显示目录设备", Modifier.weight(1f))
                    Switch(checked = catalogOnlyDevices, onCheckedChange = onCatalogFilterChange)
                }
                Text(
                    when {
                        catalogLoading -> "正在加载本地目录，暂不自动选择设备。"
                        !catalogAvailable -> "数据库不可用，可关闭筛选显示全部设备，或在设置中导入／拉取。"
                        state.device == null -> "名称匹配不代表协议支持；控制能力仍由设备探测决定。"
                        catalogMatched -> "当前设备：目录匹配"
                        else -> "当前设备：未收录名称"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (!catalogAvailable) catalogError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                referenceName?.let { Text("参考型号：$it") }
                OutlinedButton(onClick = onChooseHeadset,
                    enabled = clientAvailable && missingPermissions.isEmpty() && working == null && !catalogLoading) {
                    Text("选择耳机")
                }
                OutlinedButton(onClick = onOpenCatalog, enabled = catalogAvailable) {
                    Text("浏览目录")
                }
                Text("筛选只影响下一次自动选择，不会断开当前连接。",
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
                            state.phase.display(),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (state.phase == HeadsetPhase.READY) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Text(
                        state.device?.name?.takeIf { it.isNotBlank() }
                            ?: state.device?.address
                            ?: "等待耳机",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    if (!clientAvailable) {
                        Text("当前平台不支持蓝牙控制")
                    } else if (missingPermissions.isNotEmpty()) {
                        Text("需要蓝牙权限才能发现并连接耳机。")
                        Button(onClick = onRequestPermissions) { Text("授予权限") }
                    } else if (state.phase != HeadsetPhase.READY) {
                        Text(
                            when (state.phase) {
                                HeadsetPhase.SELECTION_REQUIRED ->
                                    "发现多台已连接的音频设备。选择你的耳机以继续连接。"
                                HeadsetPhase.DISCOVERING, HeadsetPhase.CONNECTING, HeadsetPhase.PROBING, HeadsetPhase.RECONNECTING ->
                                    "正在寻找耳机并检查连接，请稍候。"
                                else ->
                                    "尚未连接耳机。请先在系统蓝牙设置中连接耳机，然后重试。"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (state.phase == HeadsetPhase.IDLE || state.phase == HeadsetPhase.ERROR) {
                            OutlinedButton(onClick = onRetry, enabled = working == null) {
                                Text("重新连接")
                            }
                        }
                    }
                    state.error?.let { Text(it.message ?: "", color = MaterialTheme.colorScheme.error) }
                }
            }
        }

        item {
            OverviewCard("电量") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    BatteryCell("左耳", controlState.battery.left, Modifier.weight(1f))
                    BatteryCell("右耳", controlState.battery.right, Modifier.weight(1f))
                    if (controlState.battery.case != null) BatteryCell("充电盒", controlState.battery.case, Modifier.weight(1f))
                }
            }
        }

        item {
            OverviewCard("降噪模式") {
                val modes = if (ready) shownAncModes(controlState, showWind) else emptyList()
                if (controlState.ancMode == null || controlState.ancMode !in modes) {
                    Text(
                        controlState.ancMode?.display()
                            ?: if (ready && modes.isEmpty()) "未检测到可用降噪模式。"
                            else if (ready) "当前模式未知，请刷新重试"
                            else "连接后可用",
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
                                label = { Text(mode.display()) },
                                enabled = enabled,
                            )
                        }
                    }
                }
            }
        }
        item {
            OverviewCard("音频编码") {
                Text(
                    "这些开关控制耳机端编码选项；实际音频编码由系统协商。",
                    style = MaterialTheme.typography.bodySmall,
                )
                val available = ready && (GaiaIds.CODEC_TYPE in controlState.capabilities.gaiaFeatures ||
                    !controlState.capabilities.complete)
                if (!available) Text("当前会话不支持音频编码控制")
                AudioCodec.entries.forEach { codec ->
                    val actual = controlState.codecStates[codec]
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(codec.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                actual?.let { if (it) "开启" else "关闭" }
                                    ?: "未知",
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
                    "LHDC 支持取决于耳机型号和固件。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (controls.isNotEmpty()) {
            item {
                OverviewCard("扩展控制") {
                    if (OverviewControl.GAIN in controls) {
                        Text("增益", style = MaterialTheme.typography.titleSmall)
                        if (controlState.gain == null) UnknownValue()
                        ChipRow {
                            GainLevel.entries.forEach { gain ->
                                FilterChip(
                                    selected = controlState.gain == gain,
                                    onClick = { onGain(gain) },
                                    label = { Text(gain.display()) },
                                    enabled = enabled && controlState.gain != null,
                                )
                            }
                        }
                    }
                    if (OverviewControl.SPATIAL in controls) {
                        ControlSwitch(
                            "空间音频", controlState.spatialOn,
                            enabled && controlState.spatialOn != null, onSpatial,
                        )
                    }
                    if (OverviewControl.HEAD_TRACKING in controls) {
                        Text("头部追踪", style = MaterialTheme.typography.titleSmall)
                        if (controlState.headTracking == null) UnknownValue()
                        ChipRow {
                            HeadTrackingMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = controlState.headTracking == mode,
                                    onClick = { onTracking(mode) },
                                    label = { Text(mode.display()) },
                                    enabled = enabled && controlState.spatialOn == true && controlState.headTracking != null,
                                )
                            }
                        }
                    }
                    if (OverviewControl.LED in controls) {
                        ControlSwitch("LED", controlState.ledOn, enabled && controlState.ledOn != null, onLed)
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
                        Text("连接详情", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (connectionDetailsExpanded) "收起" else "展开",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    if (connectionDetailsExpanded) {
                        InfoLine("地址", state.device?.address ?: "—")
                        InfoLine(
                            "控制协议",
                            controlState.protocols.joinToString(" · ") { it.display() }.ifEmpty { "—" },
                        )
                        if (!controlState.capabilities.complete &&
                            state.phase == HeadsetPhase.READY && controlState.phase == DropPhase.READY
                        ) {
                            Text(
                                "部分能力尚未确认；可在高级诊断中测试。",
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
                "控制结果以耳机读回状态为准。",
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
private fun UnknownValue() {
    Text("状态尚未读取，可点右上角刷新。",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ControlSwitch(
    label: String,
    value: Boolean?,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(
                value?.let { if (it) "开启" else "关闭" }
                    ?: "未读取",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = value == true, onCheckedChange = onChange, enabled = enabled)
    }
}

private fun HeadsetPhase.display(): String = when (this) {
    HeadsetPhase.IDLE -> "等待连接"
    HeadsetPhase.DISCOVERING -> "发现设备中"
    HeadsetPhase.CONNECTING -> "连接中"
    HeadsetPhase.PROBING -> "检测能力中"
    HeadsetPhase.READY -> "已连接"
    HeadsetPhase.RECONNECTING -> "重新连接中"
    HeadsetPhase.SELECTION_REQUIRED -> "请选择设备"
    HeadsetPhase.ERROR -> "连接错误"
}

private fun DropProtocol.display(): String = when (this) {
    DropProtocol.GAIA_BLE -> "GAIA BLE"
    DropProtocol.SOURCE_9ECA -> "9ECA"
}

internal fun AncMode.display(): String = when (this) {
    AncMode.OFF -> "关闭"
    AncMode.NOISE_CANCELLING -> "降噪"
    AncMode.TRANSPARENCY -> "通透"
    AncMode.WIND -> "抗风噪"
    AncMode.ADAPTIVE -> "自适应"
    AncMode.LIVE -> "Live"
}

private fun GainLevel.display(): String = when (this) {
    GainLevel.LOW -> "低"
    GainLevel.MEDIUM -> "中"
    GainLevel.HIGH -> "高"
}

private fun HeadTrackingMode.display(): String = when (this) {
    HeadTrackingMode.OFF -> "关闭"
    HeadTrackingMode.THIRTY_DEGREES -> "30°"
    HeadTrackingMode.SURROUND -> "环绕"
}
