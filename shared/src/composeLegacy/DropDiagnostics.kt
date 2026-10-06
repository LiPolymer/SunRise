package ink.lipoly.app.sunrise.composeLegacy


import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ink.lipoly.app.sunrise.drop.AncMode
import ink.lipoly.app.sunrise.drop.DropEvent
import ink.lipoly.app.sunrise.drop.DropException
import ink.lipoly.app.sunrise.drop.DropPhase
import ink.lipoly.app.sunrise.drop.DropProtocol
import ink.lipoly.app.sunrise.drop.GainLevel
import ink.lipoly.app.sunrise.drop.HeadTrackingMode
import ink.lipoly.app.sunrise.drop.SourceCapability
import ink.lipoly.app.sunrise.headset.HeadsetClient
import ink.lipoly.app.sunrise.headset.HeadsetDevice
import ink.lipoly.app.sunrise.headset.HeadsetEvent
import ink.lipoly.app.sunrise.headset.HeadsetPhase
import ink.lipoly.app.sunrise.headset.HeadsetState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun DropDiagnosticsScreen(
    client: HeadsetClient? = null,
    missingPermissions: Set<String> = emptySet(),
    onRequestPermissions: () -> Unit = {},
    modifier: Modifier = Modifier,
    english: Boolean = false,
    recentEvents: List<HeadsetEvent> = emptyList(),
) {
        fun t(zh: String, en: String): String = if (english) en else zh
        val headsetState = client?.state?.collectAsState()?.value ?: HeadsetState()
        val state = headsetState.controls
        val scope = rememberCoroutineScope()
        var busy by remember(client) { mutableStateOf(false) }
        var message by remember(client, english) { mutableStateOf(t("选择设备或启动自动连接", "Choose a device or start auto-connect")) }
        var address by remember { mutableStateOf("") }
        var sourceIdText by remember { mutableStateOf("") }
        var devices by remember(client) { mutableStateOf<List<HeadsetDevice>>(emptyList()) }
        var sourceCapability by remember(client, headsetState.device?.device, headsetState.phase, state.phase) {
            mutableStateOf<SourceCapability?>(null)
        }
        LaunchedEffect(client, headsetState.phase, missingPermissions) {
            if (client != null && missingPermissions.isEmpty() && headsetState.phase == HeadsetPhase.SELECTION_REQUIRED) {
                try {
                    devices = client.discoverConnectedDevices()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    message = t("发现设备失败：", "Discovery failed: ") + (e.message ?: e::class.simpleName)
                }
            }
        }

        fun runAction(label: String, protocol: DropProtocol? = null, action: suspend HeadsetClient.() -> String) {
            val activeClient = client ?: return
            if (busy || missingPermissions.isNotEmpty()) return
            if (protocol != null && !activeClient.state.value.hasReadyControls(protocol)) return
            busy = true
            message = "$label${t("：进行中…", ": in progress…")}"
            scope.launch {
                try {
                    if (protocol != null && !activeClient.state.value.hasReadyControls(protocol)) throw DropException.NotReady()
                    message = "$label${t("：", ": ")}${activeClient.action()}"
                } catch (e: CancellationException) {
                    throw e
                } catch (_: DropException.Unverified) {
                    message = "$label${t("：已发送但未能验证，当前状态未知；请刷新。", ": sent but not verified. Current state is unknown; refresh to retry.")}"
                } catch (e: DropException.AncModeMismatch) {
                    message = "$label${t("：读回不一致，目标", ": readback mismatch; requested")} ${e.requested.label(english)}${t("，", ", ")}${t("实际", "observed")} ${e.observed.label(english)}"
                } catch (e: Exception) {
                    message = "$label${t(" 失败：", " failed: ")}${e.message ?: e::class.simpleName}"
                } finally {
                    busy = false
                }
            }
        }

        val available = client != null && missingPermissions.isEmpty() && !busy
        val gaiaReady = available && headsetState.hasReadyControls(DropProtocol.GAIA_BLE)
        val sourceReady = available && headsetState.hasReadyControls(DropProtocol.SOURCE_9ECA)

        Column(
            modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(t("设备诊断", "Device diagnostics"), style = MaterialTheme.typography.headlineSmall)
            if (client == null) {
                Text(t("未提供耳机客户端。", "No headset client was provided."))
            } else if (missingPermissions.isNotEmpty()) {
                Text(t("需要蓝牙权限：", "Bluetooth permissions required: ") + missingPermissions.joinToString { it.substringAfterLast('.') })
                Button(onClick = onRequestPermissions) { Text(t("授予权限", "Grant permission")) }
            }

            TestSection(t("连接状态", "Connection status")) {
                Text(headsetState.phase.label(english), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                Text(headsetState.device?.name?.takeIf { it.isNotBlank() } ?: headsetState.device?.address ?: t("未连接设备", "No device connected"),
                    style = MaterialTheme.typography.titleMedium)
                if (!headsetState.device?.name.isNullOrBlank()) Text(headsetState.device.address, style = MaterialTheme.typography.bodySmall)
                if (headsetState.phase == HeadsetPhase.SELECTION_REQUIRED) Text(t("检测到多台音频设备，请在下方选择。", "Multiple audio devices found. Choose one below."))
                headsetState.error?.let { Text(t("连接错误：", "Connection error: ") + (it.message ?: it::class.simpleName), color = MaterialTheme.colorScheme.error) }
                Text(message, color = MaterialTheme.colorScheme.primary)
            }

            TestSection(t("控制状态", "Control status")) {
                Text(state.phase.label(english), style = MaterialTheme.typography.titleMedium)
                state.error?.let { Text(t("控制错误：", "Control error: ") + (it.message ?: it::class.simpleName), color = MaterialTheme.colorScheme.error) }
                Text(t("协议：", "Protocols: ") + state.protocols.joinToString().ifEmpty { "—" })
                Text(t("能力：", "Capabilities: ") +
                    if (state.capabilities.complete) t("探测完成", "complete") else t("探测未完成", "incomplete"))
            }

            TestSection(t("设备", "Devices")) {
                Button(
                    onClick = { runAction(t("自动连接", "Auto-connect")) { startAutoConnect(); t("已启动", "started") } },
                    enabled = available,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(t("自动连接", "Auto-connect")) }
                OutlinedButton(onClick = {
                    runAction(t("发现设备", "Find devices")) {
                        devices = discoverConnectedDevices()
                        t("找到 ${devices.size} 台已连接音频设备", "Found ${devices.size} connected audio devices")
                    }
                }, enabled = available, modifier = Modifier.fillMaxWidth()) { Text(t("发现设备", "Find devices")) }
                devices.forEach { device ->
                    OutlinedButton(
                        onClick = { runAction(t("连接", "Connect")) { connect(device); t("已连接", "Connected") + " ${device.name ?: device.address}" } },
                        enabled = available,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("${device.name ?: t("未知设备", "Unknown device")}  ${device.address}${if (device.verified) "  ✓" else ""}")
                    }
                }
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text(t("蓝牙地址（可选）", "Bluetooth address (optional)")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(onClick = {
                    runAction(t("连接地址", "Connect address")) { connect(address.trim()); t("已连接", "Connected") + " ${address.trim()}" }
                }, enabled = available && address.isNotBlank()) { Text(t("连接此地址", "Connect to address")) }
                OutlinedButton(
                    onClick = {
                        val activeClient = client ?: return@OutlinedButton
                        scope.launch {
                            try {
                                activeClient.disconnect()
                                message = t("断开：已断开", "Disconnected")
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                message = t("断开失败：", "Disconnect failed: ") + (e.message ?: e::class.simpleName)
                            }
                        }
                    },
                    enabled = client != null && missingPermissions.isEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(t("断开", "Disconnect")) }
            }

            TestSection(t("GAIA 控制", "GAIA controls"), collapsible = true, english = english) {
                Text("GAIA ${state.capabilities.gaiaFeatures.sorted()}")
                Text(t("电量：左 ", "Battery: left ") + state.battery.left.percent() +
                    t("  右 ", "  right ") + state.battery.right.percent() +
                    t("  盒 ", "  case ") + state.battery.case.percent())
                OutlinedButton(onClick = {
                    runAction(t("读取电量", "Read battery"), DropProtocol.GAIA_BLE) { gaia.getBattery(); t("完成", "done") }
                }, enabled = gaiaReady) { Text(t("刷新电量", "Refresh battery")) }

                Text(t("降噪：", "ANC: ") + (state.ancMode?.label(english) ?: t("未读取", "not read")))
                OutlinedButton(onClick = {
                    runAction(t("读取降噪", "Read ANC"), DropProtocol.GAIA_BLE) { gaia.getAncMode().label(english) }
                }, enabled = gaiaReady && state.capabilities.ancModes.isNotEmpty()) { Text(t("读取降噪", "Read ANC")) }
                ChoiceRow(
                    values = AncMode.entries.filter { it in state.capabilities.ancModes },
                    selected = state.ancMode,
                    enabled = gaiaReady,
                    label = { it.label(english) },
                    onClick = { mode -> runAction(t("设置降噪", "Set ANC"), DropProtocol.GAIA_BLE) { t("读回已确认：", "Readback verified: ") + gaia.setAncMode(mode).label(english) } },
                )

                Text(t("增益：", "Gain: ") + (state.gain?.label(english) ?: t("未读取", "not read")))
                OutlinedButton(onClick = {
                    runAction(t("读取增益", "Read gain"), DropProtocol.GAIA_BLE) { gaia.getGain().label(english) }
                }, enabled = gaiaReady) { Text(t("读取增益", "Read gain")) }
                ChoiceRow(
                    values = GainLevel.entries,
                    selected = state.gain,
                    enabled = gaiaReady,
                    label = { it.label(english) },
                    onClick = { level -> runAction(t("设置增益", "Set gain"), DropProtocol.GAIA_BLE) { gaia.setGain(level).label(english) } },
                )

                ToggleTestRow("LED", state.ledOn, gaiaReady, english,
                    onRead = { runAction(t("读取 LED", "Read LED"), DropProtocol.GAIA_BLE) { gaia.isLedOn().onOff(english) } },
                    onToggle = { on -> runAction(t("设置 LED", "Set LED"), DropProtocol.GAIA_BLE) { gaia.setLedOn(on).onOff(english) } })
                ToggleTestRow(t("空间音频", "Spatial audio"), state.spatialOn, gaiaReady, english,
                    onRead = { runAction(t("读取空间音频", "Read spatial audio"), DropProtocol.GAIA_BLE) { gaia.isSpatialOn().onOff(english) } },
                    onToggle = { on -> runAction(t("设置空间音频", "Set spatial audio"), DropProtocol.GAIA_BLE) { gaia.setSpatialOn(on).onOff(english) } })

                Text(t("头部追踪：", "Head tracking: ") + (state.headTracking?.label(english) ?: t("未读取", "not read")))
                OutlinedButton(onClick = {
                    runAction(t("读取头部追踪", "Read head tracking"), DropProtocol.GAIA_BLE) { gaia.getHeadTracking().label(english) }
                }, enabled = gaiaReady) { Text(t("读取头部追踪", "Read head tracking")) }
                ChoiceRow(
                    values = HeadTrackingMode.entries,
                    selected = state.headTracking,
                    enabled = gaiaReady,
                    label = { it.label(english) },
                    onClick = { mode -> runAction(t("设置头部追踪", "Set head tracking"), DropProtocol.GAIA_BLE) { gaia.setHeadTracking(mode).label(english) } },
                )
            }

            TestSection(t("9ECA 音源", "9ECA audio source"), collapsible = true, english = english) {
                Text("9ECA ${state.capabilities.sourceFeatures}")
                Text(t("当前音源 ID：", "Current source ID: ") + (state.sourceStatus?.currentSource?.toString() ?: t("未读取", "not read")))
                state.sourceStatus?.let {
                    Text(t("目标 ", "Target ") + "${it.targetSource}" + t("，切换状态 ", ", transition ") +
                        "${it.transitionState}" + t("，结果码 ", ", status ") + "${it.statusCode}")
                }
                ActionRow {
                    OutlinedButton(onClick = {
                        runAction(t("读取音源", "Read source"), DropProtocol.SOURCE_9ECA) { t("当前 ID ", "Current ID ") + source.getAudioSource().currentSource }
                    }, enabled = sourceReady) { Text(t("读取当前音源", "Read current source")) }
                    OutlinedButton(onClick = {
                        runAction(t("读取可用音源", "Read available sources"), DropProtocol.SOURCE_9ECA) {
                            sourceCapability = source.readSourceCapability()
                            t("找到 ${sourceCapability?.entries?.size ?: 0} 个音源", "Found ${sourceCapability?.entries?.size ?: 0} sources")
                        }
                    }, enabled = sourceReady) { Text(t("读取可用音源", "Read available sources")) }
                }
                sourceCapability?.entries?.forEach { entry ->
                    OutlinedButton(onClick = {
                        runAction(t("切换音源", "Switch source"), DropProtocol.SOURCE_9ECA) {
                            val result = source.setAudioSource(entry.sourceId)
                            t("当前 ID ", "Current ID ") + "${result.currentSource}" + t("，结果码 ", ", status ") + result.statusCode
                        }
                    }, enabled = sourceReady && entry.sourceId != state.sourceStatus?.currentSource) {
                        Text(t("切换到 ID ", "Switch to ID ") + "${entry.sourceId}" + t("（标志 ", " (flags ") + "${entry.flags}" + t("）", ")"))
                    }
                }
                if (sourceCapability == null) Text(t("可读取设备提供的音源列表，或手动输入已知 ID。", "Read the device source list or enter a known ID."))
                val sourceId = sourceIdText.toIntOrNull()
                OutlinedTextField(
                    value = sourceIdText,
                    onValueChange = { sourceIdText = it },
                    label = { Text(t("手动音源 ID（0–255）", "Source ID (0–255)")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(onClick = {
                    sourceId?.let { id ->
                        runAction(t("切换音源", "Switch source"), DropProtocol.SOURCE_9ECA) {
                            val result = source.setAudioSource(id)
                            t("当前 ID ", "Current ID ") + "${result.currentSource}" + t("，结果码 ", ", status ") + result.statusCode
                        }
                    }
                }, enabled = sourceReady && sourceId != null && sourceId in 0..255) { Text(t("切换到此 ID", "Switch to this ID")) }
                state.volume?.let { Text(t("音量：", "Volume: ") + "${it.current} (${it.minimum}–${it.maximum})") }
                state.presetEq?.let { Text(t("预设 EQ：", "Preset EQ: ") + "${it.current} / ${it.count}") }
                state.micGain?.let { Text(t("麦克风增益：", "Mic gain: ") + "${it.currentDeciDb / 10f} dB") }
                ActionRow {
                    OutlinedButton(onClick = {
                        runAction(t("读取音量", "Read volume"), DropProtocol.SOURCE_9ECA) { t("当前 ", "Current ") + source.getVolume().current }
                    }, enabled = sourceReady) { Text(t("读音量", "Read volume")) }
                    OutlinedButton(onClick = {
                        runAction(t("读取预设 EQ", "Read preset EQ"), DropProtocol.SOURCE_9ECA) { t("当前 ", "Current ") + source.getPresetEq().current }
                    }, enabled = sourceReady) { Text(t("读预设 EQ", "Read preset EQ")) }
                    OutlinedButton(onClick = {
                        runAction(t("读取麦克风增益", "Read mic gain"), DropProtocol.SOURCE_9ECA) { "${source.getMicGain().currentDeciDb / 10f} dB" }
                    }, enabled = sourceReady) { Text(t("读麦克风增益", "Read mic gain")) }
                }
            }

            TestSection(t("最近通知", "Recent notifications"), collapsible = true, english = english) {
                if (recentEvents.isEmpty()) Text(t("暂无通知", "No notifications"))
                recentEvents.asReversed().forEach { Text(it.describe(english), style = MaterialTheme.typography.bodySmall) }
            }
        }
}

@Composable
private fun TestSection(
    title: String,
    collapsible: Boolean = false,
    english: Boolean = false,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(!collapsible) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (collapsible) {
                val actionLabel = if (expanded) {
                    if (english) "Collapse" else "收起"
                } else {
                    if (english) "Expand" else "展开"
                }
                OutlinedButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("$title · $actionLabel", style = MaterialTheme.typography.titleMedium)
                }
            } else {
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            if (expanded) content()
        }
    }
}

@Composable
private fun ActionRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
private fun <T> ChoiceRow(
    values: List<T>, selected: T?, enabled: Boolean,
    label: (T) -> String, onClick: (T) -> Unit,
) {
    ActionRow {
        values.forEach { value ->
            if (value == selected) {
                Button(onClick = { onClick(value) }, enabled = enabled) { Text(label(value)) }
            } else {
                OutlinedButton(onClick = { onClick(value) }, enabled = enabled) { Text(label(value)) }
            }
        }
    }
}

@Composable
private fun ToggleTestRow(
    title: String, value: Boolean?, enabled: Boolean, english: Boolean,
    onRead: () -> Unit, onToggle: (Boolean) -> Unit,
) {
    Text("$title: ${value?.onOff(english) ?: if (english) "not read" else "未读取"}")
    ActionRow {
        OutlinedButton(onClick = onRead, enabled = enabled) { Text(if (english) "Read $title" else "读取 $title") }
        Button(onClick = { onToggle(value != true) }, enabled = enabled && value != null) {
            Text(if (value == true) { if (english) "Turn off" else "关闭" } else { if (english) "Turn on" else "开启" })
        }
    }
}
private fun HeadsetState.hasReadyControls(protocol: DropProtocol): Boolean =
    phase == HeadsetPhase.READY && controls.phase == DropPhase.READY && protocol in controls.protocols


private fun DropPhase.label(english: Boolean): String = when (this) {
    DropPhase.IDLE -> if (english) "Idle" else "空闲"
    DropPhase.PROBING -> if (english) "Probing" else "探测能力"
    DropPhase.READY -> if (english) "Ready" else "已就绪"
    DropPhase.ERROR -> if (english) "Error" else "错误"
}

private fun HeadsetPhase.label(english: Boolean): String = when (this) {
    HeadsetPhase.IDLE -> if (english) "Idle" else "空闲"
    HeadsetPhase.DISCOVERING -> if (english) "Discovering" else "发现设备"
    HeadsetPhase.CONNECTING -> if (english) "Connecting" else "连接中"
    HeadsetPhase.PROBING -> if (english) "Probing" else "探测能力"
    HeadsetPhase.READY -> if (english) "Ready" else "已就绪"
    HeadsetPhase.RECONNECTING -> if (english) "Reconnecting" else "重连中"
    HeadsetPhase.SELECTION_REQUIRED -> if (english) "Selection required" else "需要选择设备"
    HeadsetPhase.ERROR -> if (english) "Error" else "错误"
}

private fun AncMode.label(english: Boolean): String = when (this) {
    AncMode.OFF -> if (english) "Off" else "关闭"
    AncMode.NOISE_CANCELLING -> if (english) "Noise cancelling" else "降噪"
    AncMode.TRANSPARENCY -> if (english) "Transparency" else "通透"
    AncMode.WIND -> if (english) "Wind" else "抗风"
    AncMode.ADAPTIVE -> if (english) "Adaptive" else "自适应"
    AncMode.LIVE -> "Live"
}

private fun GainLevel.label(english: Boolean): String = when (this) {
    GainLevel.LOW -> if (english) "Low" else "低"
    GainLevel.MEDIUM -> if (english) "Medium" else "中"
    GainLevel.HIGH -> if (english) "High" else "高"
}

private fun HeadTrackingMode.label(english: Boolean): String = when (this) {
    HeadTrackingMode.OFF -> if (english) "Off" else "关闭"
    HeadTrackingMode.THIRTY_DEGREES -> "30°"
    HeadTrackingMode.SURROUND -> if (english) "Surround" else "环绕"
}

private fun Boolean.onOff(english: Boolean): String = if (this) { if (english) "On" else "开" } else { if (english) "Off" else "关" }
private fun Int?.percent(): String = this?.let { "$it%" } ?: "—"

private fun HeadsetEvent.describe(english: Boolean): String = when (this) {
    is HeadsetEvent.Control -> event.describe(english)
    is HeadsetEvent.Error -> (if (english) "Error: " else "错误：") + (cause.message ?: cause::class.simpleName)
}

private fun DropEvent.describe(english: Boolean): String = when (this) {
    is DropEvent.Error -> (if (english) "Error: " else "错误：") + cause.message
    is DropEvent.GaiaNotification ->
        "GAIA ${packet.feature}/${packet.command}：${packet.payload.hex()}"
    is DropEvent.SourceNotification -> "9ECA $commandId：${payload.hex()}"
}

private fun ByteArray.hex(): String = joinToString(" ") {
    (it.toInt() and 0xff).toString(16).padStart(2, '0')
}
