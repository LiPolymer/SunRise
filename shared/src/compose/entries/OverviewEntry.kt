package ink.lipoly.app.sunrise.compose.entries

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import ink.lipoly.app.sunrise.catalog.CatalogState
import ink.lipoly.app.sunrise.catalog.matchesCatalogDevice
import ink.lipoly.app.sunrise.compose.OurNavStack
import ink.lipoly.app.sunrise.compose.errorMessage
import ink.lipoly.app.sunrise.compose.BatteryCell
import ink.lipoly.app.sunrise.compose.ListItemCard
import ink.lipoly.app.sunrise.compose.display
import ink.lipoly.app.sunrise.presentation.*
import ink.lipoly.app.sunrise.drop.*
import ink.lipoly.app.sunrise.settings.UiSettings
import ink.lipoly.app.sunrise.headset.HeadsetPhase
import ink.lipoly.app.sunrise.headset.HeadsetState

@Composable
internal fun OverviewEntry(
    headsetState: HeadsetState,
    catalogState: CatalogState,
    navStack: NavBackStack<NavKey>,
    missingPermissions: Set<String>,
    onRequestPermissions: () -> Unit,
    session: PresentationSession,
    headsetPresentationState: HeadsetPresentationState,
    settings: UiSettings,
    onSettingsChange: (UiSettings) -> Unit,
    clientAvailable: Boolean,
    referenceName: String?,
    codecBlocked: Boolean,
    onCodec: (AudioCodec, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val controlState = headsetState.controls
    val ready = headsetState.phase == HeadsetPhase.READY && controlState.hasReadyGaia()
    val usable = ready && headsetPresentationState.working == null && missingPermissions.isEmpty()
    val controls = if (ready) confirmedOverviewControls(controlState, headsetPresentationState.confirmedReads) else emptySet()
    var connectionDetailsExpanded by remember { mutableStateOf(false) }

    LazyColumn(modifier) {
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
                    if (!clientAvailable) {
                        Text("当前平台不支持蓝牙控制")
                    } else if (missingPermissions.isNotEmpty()) {
                        Text("需要蓝牙权限才能发现并连接耳机。")
                        Button(onClick = onRequestPermissions) { Text("授予权限") }
                    } else if (headsetState.phase != HeadsetPhase.READY) {
                        Text(when (headsetState.phase) {
                            HeadsetPhase.SELECTION_REQUIRED -> "发现多台已连接的音频设备。选择你的耳机以继续连接。"
                            HeadsetPhase.DISCOVERING, HeadsetPhase.CONNECTING, HeadsetPhase.PROBING, HeadsetPhase.RECONNECTING ->
                                "正在寻找耳机并检查连接，请稍候。"
                            else -> "尚未连接耳机。请先在系统蓝牙设置中连接耳机，然后重试。"
                        })
                        if (headsetState.phase == HeadsetPhase.IDLE || headsetState.phase == HeadsetPhase.ERROR) {
                            OutlinedButton(
                                onClick = session.headset::retryConnection,
                                enabled = headsetPresentationState.working == null,
                            ) { Text("重新连接") }
                        }
                    }
                    headsetState.error?.let { Text(errorMessage(it), color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        item {
            ListItemCard {
                Row(
                    modifier = Modifier
                        .padding(16.dp)
                ) {
                    val modes = if (ready) shownAncModes(controlState, settings.showWind) else emptyList()
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
                                    label = { Text(mode.display()) },
                                    enabled = usable,
                                )
                            }
                        }
                    }
                }
            }
        }
        item {
            ListItemCard {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("设备目录", style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("仅显示目录设备", Modifier.weight(1f))
                        Switch(
                            checked = settings.catalogOnlyDevices,
                            onCheckedChange = { onSettingsChange(settings.copy(catalogOnlyDevices = it)) },
                        )
                    }
                    Text(when {
                        catalogState.loading -> "正在加载本地目录，暂不自动选择设备。"
                        catalogState.snapshot == null -> "数据库不可用，可关闭筛选显示全部设备，或在设置中导入／拉取。"
                        headsetState.device == null -> "名称匹配不代表协议支持；控制能力仍由设备探测决定。"
                        matchesCatalogDevice(catalogState.snapshot, headsetState.device.name) -> "当前设备：目录匹配"
                        else -> "当前设备：未收录名称"
                    }, style = MaterialTheme.typography.bodySmall)
                    if (catalogState.snapshot == null) catalogState.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                    referenceName?.let { Text("参考型号：$it") }
                    OutlinedButton(
                        onClick = session.headset::chooseHeadset,
                        enabled = clientAvailable && missingPermissions.isEmpty() &&
                            headsetPresentationState.working == null && !catalogState.loading,
                    ) { Text("选择耳机") }
                    OutlinedButton(
                        onClick = { navStack.add(OurNavStack.Route.Catalog) },
                        enabled = catalogState.snapshot != null,
                    ) { Text("浏览目录") }
                    Text("筛选只影响下一次自动选择，不会断开当前连接。", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            ListItemCard {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("音频编码", style = MaterialTheme.typography.titleMedium)
                    Text("这些开关控制耳机端编码选项；实际音频编码由系统协商。", style = MaterialTheme.typography.bodySmall)
                    val available = ready && (GaiaIds.CODEC_TYPE in controlState.capabilities.gaiaFeatures ||
                        !controlState.capabilities.complete)
                    if (!available) Text("当前会话不支持音频编码控制")
                    AudioCodec.entries.forEach { codec ->
                        val actual = controlState.codecStates[codec]
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(codec.name, style = MaterialTheme.typography.titleSmall)
                                Text(actual?.let { if (it) "开启" else "关闭" } ?: "未知", style = MaterialTheme.typography.bodySmall)
                            }
                            if (actual != null) Switch(
                                checked = actual,
                                onCheckedChange = { onCodec(codec, it) },
                                enabled = available && usable && !codecBlocked,
                            )
                        }
                    }
                    Text("LHDC 支持取决于耳机型号和固件。", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (controls.isNotEmpty()) item {
            ListItemCard {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("扩展控制", style = MaterialTheme.typography.titleMedium)
                    if (OverviewControl.GAIN in controls) {
                        Text("增益", style = MaterialTheme.typography.titleSmall)
                        if (controlState.gain == null) Text("状态尚未读取，可点右上角刷新。")
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GainLevel.entries.forEach { gain ->
                                FilterChip(
                                    selected = controlState.gain == gain,
                                    onClick = { session.headset.runAction("设置增益", control = true) { gaia.setGain(gain) } },
                                    label = { Text(gain.display()) },
                                    enabled = usable && controlState.gain != null,
                                )
                            }
                        }
                    }
                    if (OverviewControl.SPATIAL in controls) ControlSwitch(
                        "空间音频", controlState.spatialOn, usable && controlState.spatialOn != null,
                    ) { on -> session.headset.runAction("空间音频", control = true) { gaia.setSpatialOn(on) } }
                    if (OverviewControl.HEAD_TRACKING in controls) {
                        Text("头部追踪", style = MaterialTheme.typography.titleSmall)
                        if (controlState.headTracking == null) Text("状态尚未读取，可点右上角刷新。")
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            HeadTrackingMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = controlState.headTracking == mode,
                                    onClick = { session.headset.runAction("头部追踪", control = true) { gaia.setHeadTracking(mode) } },
                                    label = { Text(mode.display()) },
                                    enabled = usable && controlState.spatialOn == true && controlState.headTracking != null,
                                )
                            }
                        }
                    }
                    if (OverviewControl.LED in controls) ControlSwitch(
                        "LED", controlState.ledOn, usable && controlState.ledOn != null,
                    ) { on -> session.headset.runAction("LED", control = true) { gaia.setLedOn(on) } }
                }
            }
        }
        item {
            ListItemCard {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("连接详情", style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { connectionDetailsExpanded = !connectionDetailsExpanded }) {
                            Text(if (connectionDetailsExpanded) "收起" else "展开")
                        }
                    }
                    if (connectionDetailsExpanded) {
                        Text("地址：${headsetState.device?.address ?: "—"}")
                        Text("控制协议：" + controlState.protocols.joinToString(" · ") {
                            when (it) {
                                DropProtocol.GAIA_BLE -> "GAIA BLE"
                                DropProtocol.SOURCE_9ECA -> "9ECA"
                            }
                        }.ifEmpty { "—" })
                        if (!controlState.capabilities.complete && headsetState.phase == HeadsetPhase.READY &&
                            controlState.phase == DropPhase.READY) {
                            Text("部分能力尚未确认；可在高级诊断中测试。", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        item {
            Text("控制结果以耳机读回状态为准。", Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

@Composable
private fun ControlSwitch(label: String, value: Boolean?, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(value?.let { if (it) "开启" else "关闭" } ?: "未读取", style = MaterialTheme.typography.bodySmall)
        }
        if (value != null) Switch(checked = value, onCheckedChange = onChange, enabled = enabled)
    }
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
