package ink.lipoly.app.sunrise.compose.entries


import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ink.lipoly.app.sunrise.compose.ListItemCard
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
internal fun DiagnosticsEntry(
    client: HeadsetClient? = null,
    missingPermissions: Set<String> = emptySet(),
    onRequestPermissions: () -> Unit = {},
    modifier: Modifier = Modifier,
    recentEvents: List<HeadsetEvent> = emptyList(),
) {
    val headsetState = client?.state?.collectAsState()?.value ?: HeadsetState()
    val state = headsetState.controls
    val scope = rememberCoroutineScope()
    var busy by remember(client) { mutableStateOf(false) }
    var message by remember(client) { mutableStateOf("选择设备或启动自动连接") }
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
                message = "发现设备失败：" + (e.message ?: e::class.simpleName)
            }
        }
    }

    fun runAction(label: String, protocol: DropProtocol? = null, action: suspend HeadsetClient.() -> String) {
        val activeClient = client ?: return
        if (busy || missingPermissions.isNotEmpty()) return
        if (protocol != null && !activeClient.state.value.hasReadyControls(protocol)) return
        busy = true
        message = "$label：进行中…"
        scope.launch {
            try {
                if (protocol != null && !activeClient.state.value.hasReadyControls(protocol)) throw DropException.NotReady()
                message = "$label：${activeClient.action()}"
            } catch (e: CancellationException) {
                throw e
            } catch (_: DropException.Unverified) {
                message = "$label：已发送但未能验证，当前状态未知；请刷新。"
            } catch (e: DropException.AncModeMismatch) {
                message = "$label：读回不一致，目标 ${e.requested.label()}，实际 ${e.observed.label()}"
            } catch (e: Exception) {
                message = "$label 失败：${e.message ?: e::class.simpleName}"
            } finally {
                busy = false
            }
        }
    }

    val available = client != null && missingPermissions.isEmpty() && !busy
    val gaiaReady = available && headsetState.hasReadyControls(DropProtocol.GAIA_BLE)
    val sourceReady = available && headsetState.hasReadyControls(DropProtocol.SOURCE_9ECA)

    LazyColumn(modifier = modifier.fillMaxSize()) {
        item(key = "heading") {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("设备诊断", style = MaterialTheme.typography.headlineSmall)
                if (client == null) {
                    Text("未提供耳机客户端。")
                } else if (missingPermissions.isNotEmpty()) {
                    Text("需要蓝牙权限：" + missingPermissions.joinToString { it.substringAfterLast('.') })
                    Button(onClick = onRequestPermissions) { Text("授予权限") }
                }
            }
        }

        item(key = "connection") {
            TestSection("连接状态") {
                Text(headsetState.phase.label(), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                Text(headsetState.device?.name?.takeIf { it.isNotBlank() } ?: headsetState.device?.address ?: "未连接设备",
                    style = MaterialTheme.typography.titleMedium)
                if (!headsetState.device?.name.isNullOrBlank()) Text(headsetState.device.address, style = MaterialTheme.typography.bodySmall)
                if (headsetState.phase == HeadsetPhase.SELECTION_REQUIRED) Text("检测到多台音频设备，请在下方选择。")
                headsetState.error?.let { Text("连接错误：" + (it.message ?: it::class.simpleName), color = MaterialTheme.colorScheme.error) }
                Text(message, color = MaterialTheme.colorScheme.primary)
            }
        }

        item(key = "controls") {
            TestSection("控制状态") {
                Text(state.phase.label(), style = MaterialTheme.typography.titleMedium)
                state.error?.let { Text("控制错误：" + (it.message ?: it::class.simpleName), color = MaterialTheme.colorScheme.error) }
                Text("协议：" + state.protocols.joinToString().ifEmpty { "—" })
                Text("能力：" +
                    if (state.capabilities.complete) "探测完成" else "探测未完成")
            }
        }

        item(key = "device") {
            TestSection("设备") {
                Button(
                    onClick = { runAction("自动连接") { startAutoConnect(); "已启动" } },
                    enabled = available,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("自动连接") }
                OutlinedButton(onClick = {
                    runAction("发现设备") {
                        devices = discoverConnectedDevices()
                        "找到 ${devices.size} 台已连接音频设备"
                    }
                }, enabled = available, modifier = Modifier.fillMaxWidth()) { Text("发现设备") }
                devices.forEach { device ->
                    OutlinedButton(
                        onClick = { runAction("连接") { connect(device); "已连接" + " ${device.name ?: device.address}" } },
                        enabled = available,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("${device.name ?: "未知设备"}  ${device.address}${if (device.verified) "  ✓" else ""}")
                    }
                }
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("蓝牙地址（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(onClick = {
                    runAction("连接地址") { connect(address.trim()); "已连接" + " ${address.trim()}" }
                }, enabled = available && address.isNotBlank()) { Text("连接此地址") }
                OutlinedButton(
                    onClick = {
                        val activeClient = client ?: return@OutlinedButton
                        scope.launch {
                            try {
                                activeClient.disconnect()
                                message = "断开：已断开"
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                message = "断开失败：" + (e.message ?: e::class.simpleName)
                            }
                        }
                    },
                    enabled = client != null && missingPermissions.isEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("断开") }
            }
        }

        item(key = "gaia") {
            TestSection("GAIA 控制", collapsible = true) {
                Text("GAIA ${state.capabilities.gaiaFeatures.sorted()}")
                Text("电量：左 " + state.battery.left.percent() +
                    "  右 " + state.battery.right.percent() +
                    "  盒 " + state.battery.case.percent())
                OutlinedButton(onClick = {
                    runAction("读取电量", DropProtocol.GAIA_BLE) { gaia.getBattery(); "完成" }
                }, enabled = gaiaReady) { Text("刷新电量") }

                Text("降噪：" + (state.ancMode?.label() ?: "未读取"))
                OutlinedButton(onClick = {
                    runAction("读取降噪", DropProtocol.GAIA_BLE) { gaia.getAncMode().label() }
                }, enabled = gaiaReady && state.capabilities.ancModes.isNotEmpty()) { Text("读取降噪") }
                ChoiceRow(
                    values = AncMode.entries.filter { it in state.capabilities.ancModes },
                    selected = state.ancMode,
                    enabled = gaiaReady,
                    label = { it.label() },
                    onClick = { mode -> runAction("设置降噪", DropProtocol.GAIA_BLE) { "读回已确认：" + gaia.setAncMode(mode).label() } },
                )

                Text("增益：" + (state.gain?.label() ?: "未读取"))
                OutlinedButton(onClick = {
                    runAction("读取增益", DropProtocol.GAIA_BLE) { gaia.getGain().label() }
                }, enabled = gaiaReady) { Text("读取增益") }
                ChoiceRow(
                    values = GainLevel.entries,
                    selected = state.gain,
                    enabled = gaiaReady,
                    label = { it.label() },
                    onClick = { level -> runAction("设置增益", DropProtocol.GAIA_BLE) { gaia.setGain(level).label() } },
                )

                ToggleTestRow("LED", state.ledOn, gaiaReady,
                    onRead = { runAction("读取 LED", DropProtocol.GAIA_BLE) { gaia.isLedOn().onOff() } },
                    onToggle = { on -> runAction("设置 LED", DropProtocol.GAIA_BLE) { gaia.setLedOn(on).onOff() } })
                ToggleTestRow("空间音频", state.spatialOn, gaiaReady,
                    onRead = { runAction("读取空间音频", DropProtocol.GAIA_BLE) { gaia.isSpatialOn().onOff() } },
                    onToggle = { on -> runAction("设置空间音频", DropProtocol.GAIA_BLE) { gaia.setSpatialOn(on).onOff() } })

                Text("头部追踪：" + (state.headTracking?.label() ?: "未读取"))
                OutlinedButton(onClick = {
                    runAction("读取头部追踪", DropProtocol.GAIA_BLE) { gaia.getHeadTracking().label() }
                }, enabled = gaiaReady) { Text("读取头部追踪") }
                ChoiceRow(
                    values = HeadTrackingMode.entries,
                    selected = state.headTracking,
                    enabled = gaiaReady,
                    label = { it.label() },
                    onClick = { mode -> runAction("设置头部追踪", DropProtocol.GAIA_BLE) { gaia.setHeadTracking(mode).label() } },
                )
            }
        }

        item(key = "source") {
            TestSection("9ECA 音源", collapsible = true) {
                Text("9ECA ${state.capabilities.sourceFeatures}")
                Text("当前音源 ID：" + (state.sourceStatus?.currentSource?.toString() ?: "未读取"))
                state.sourceStatus?.let {
                    Text("目标 " + "${it.targetSource}" + "，切换状态 " +
                        "${it.transitionState}" + "，结果码 " + "${it.statusCode}")
                }
                ActionRow {
                    OutlinedButton(onClick = {
                        runAction("读取音源", DropProtocol.SOURCE_9ECA) { "当前 ID " + source.getAudioSource().currentSource }
                    }, enabled = sourceReady) { Text("读取当前音源") }
                    OutlinedButton(onClick = {
                        runAction("读取可用音源", DropProtocol.SOURCE_9ECA) {
                            sourceCapability = source.readSourceCapability()
                            "找到 ${sourceCapability?.entries?.size ?: 0} 个音源"
                        }
                    }, enabled = sourceReady) { Text("读取可用音源") }
                }
                sourceCapability?.entries?.forEach { entry ->
                    OutlinedButton(onClick = {
                        runAction("切换音源", DropProtocol.SOURCE_9ECA) {
                            val result = source.setAudioSource(entry.sourceId)
                            "当前 ID " + "${result.currentSource}" + "，结果码 " + result.statusCode
                        }
                    }, enabled = sourceReady && entry.sourceId != state.sourceStatus?.currentSource) {
                        Text("切换到 ID " + "${entry.sourceId}" + "（标志 " + "${entry.flags}" + "）")
                    }
                }
                if (sourceCapability == null) Text("可读取设备提供的音源列表，或手动输入已知 ID。")
                val sourceId = sourceIdText.toIntOrNull()
                OutlinedTextField(
                    value = sourceIdText,
                    onValueChange = { sourceIdText = it },
                    label = { Text("手动音源 ID（0–255）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(onClick = {
                    sourceId?.let { id ->
                        runAction("切换音源", DropProtocol.SOURCE_9ECA) {
                            val result = source.setAudioSource(id)
                            "当前 ID " + "${result.currentSource}" + "，结果码 " + result.statusCode
                        }
                    }
                }, enabled = sourceReady && sourceId != null && sourceId in 0..255) { Text("切换到此 ID") }
                state.volume?.let { Text("音量：" + "${it.current} (${it.minimum}–${it.maximum})") }
                state.presetEq?.let { Text("预设 EQ：" + "${it.current} / ${it.count}") }
                state.micGain?.let { Text("麦克风增益：" + "${it.currentDeciDb / 10f} dB") }
                ActionRow {
                    OutlinedButton(onClick = {
                        runAction("读取音量", DropProtocol.SOURCE_9ECA) { "当前 " + source.getVolume().current }
                    }, enabled = sourceReady) { Text("读音量") }
                    OutlinedButton(onClick = {
                        runAction("读取预设 EQ", DropProtocol.SOURCE_9ECA) { "当前 " + source.getPresetEq().current }
                    }, enabled = sourceReady) { Text("读预设 EQ") }
                    OutlinedButton(onClick = {
                        runAction("读取麦克风增益", DropProtocol.SOURCE_9ECA) { "${source.getMicGain().currentDeciDb / 10f} dB" }
                    }, enabled = sourceReady) { Text("读麦克风增益") }
                }
            }
        }

        item(key = "events") {
            TestSection("最近通知", collapsible = true) {
                if (recentEvents.isEmpty()) Text("暂无通知")
                recentEvents.asReversed().forEach { Text(it.describe(), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun TestSection(
    title: String,
    collapsible: Boolean = false,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(!collapsible) }
    ListItemCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (collapsible) {
                val actionLabel = if (expanded) {
                    "收起"
                } else {
                    "展开"
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
    title: String, value: Boolean?, enabled: Boolean,
    onRead: () -> Unit, onToggle: (Boolean) -> Unit,
) {
    Text("$title: ${value?.onOff() ?: "未读取"}")
    ActionRow {
        OutlinedButton(onClick = onRead, enabled = enabled) { Text("读取 $title") }
        Button(onClick = { onToggle(value != true) }, enabled = enabled && value != null) {
            Text(if (value == true) { "关闭" } else { "开启" })
        }
    }
}
private fun HeadsetState.hasReadyControls(protocol: DropProtocol): Boolean =
    phase == HeadsetPhase.READY && controls.phase == DropPhase.READY && protocol in controls.protocols


private fun DropPhase.label(): String = when (this) {
    DropPhase.IDLE -> "空闲"
    DropPhase.PROBING -> "探测能力"
    DropPhase.READY -> "已就绪"
    DropPhase.ERROR -> "错误"
}

private fun HeadsetPhase.label(): String = when (this) {
    HeadsetPhase.IDLE -> "空闲"
    HeadsetPhase.DISCOVERING -> "发现设备"
    HeadsetPhase.CONNECTING -> "连接中"
    HeadsetPhase.PROBING -> "探测能力"
    HeadsetPhase.READY -> "已就绪"
    HeadsetPhase.RECONNECTING -> "重连中"
    HeadsetPhase.SELECTION_REQUIRED -> "需要选择设备"
    HeadsetPhase.ERROR -> "错误"
}

private fun AncMode.label(): String = when (this) {
    AncMode.OFF -> "关闭"
    AncMode.NOISE_CANCELLING -> "降噪"
    AncMode.TRANSPARENCY -> "通透"
    AncMode.WIND -> "抗风"
    AncMode.ADAPTIVE -> "自适应"
    AncMode.LIVE -> "Live"
}

private fun GainLevel.label(): String = when (this) {
    GainLevel.LOW -> "低"
    GainLevel.MEDIUM -> "中"
    GainLevel.HIGH -> "高"
}

private fun HeadTrackingMode.label(): String = when (this) {
    HeadTrackingMode.OFF -> "关闭"
    HeadTrackingMode.THIRTY_DEGREES -> "30°"
    HeadTrackingMode.SURROUND -> "环绕"
}

private fun Boolean.onOff(): String = if (this) { "开" } else { "关" }
private fun Int?.percent(): String = this?.let { "$it%" } ?: "—"

private fun HeadsetEvent.describe(): String = when (this) {
    is HeadsetEvent.Control -> event.describe()
    is HeadsetEvent.Error -> ("错误：") + (cause.message ?: cause::class.simpleName)
}

private fun DropEvent.describe(): String = when (this) {
    is DropEvent.Error -> ("错误：") + cause.message
    is DropEvent.GaiaNotification ->
        "GAIA ${packet.feature}/${packet.command}：${packet.payload.hex()}"
    is DropEvent.SourceNotification -> "9ECA $commandId：${payload.hex()}"
}

private fun ByteArray.hex(): String = joinToString(" ") {
    (it.toInt() and 0xff).toString(16).padStart(2, '0')
}
