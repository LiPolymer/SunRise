# 蓝牙通讯、Drop 控制与耳机应用接入

三个包仍位于同一个 `shared` 模块，包名前缀为 `ink.lipoly.app.sunrise`：

| 包 | 入口 | 职责与所有权 |
| --- | --- | --- |
| `blueConnector` | [BtManager / BtDevice / GattSession](../blueConnector/BtApi.kt)、[createBtManager](../blueConnector/BtPlatform.kt) | 平台发现、配对请求和设备级 GATT；不识别 GAIA/9ECA，也不选择耳机 |
| `drop` | [DropController](DropController.kt)、[GaiaControls / SourceControls](DropApi.kt) | 对调用方提供的已连接 GATT 会话探测能力、匹配协议回复和维护功能状态 |
| `headset` | [HeadsetClient](../headset/HeadsetClient.kt)、[HeadsetState](../headset/HeadsetApi.kt) | 本应用的单耳机选择、音频地址→BLE 端点关联、连接/重试和电量轮询 |

依赖方向为 `headset → drop → blueConnector`，应用也直接持有通讯管理器。只有 GATT；RFCOMM 路径已删除。没有品牌名单或发现前的协议筛选。

通讯包的完整名称为 `ink.lipoly.app.sunrise.blueConnector`。包和目录已迁移；`Bt*`、`Gatt*` 类型与 `createBtManager` 的名称、签名及行为均不变，示例中的局部变量 `bt` 也无需改名。

## Android 应用接入

权限声明沿用 [AndroidManifest.xml](../../../android-app/src/AndroidManifest.xml)：Android 12+ 的 CONNECT/SCAN，以及旧系统扫描所需的定位权限。`BtPermissions.missing(context, scan = true)` 返回完整权限名；它只检查，不申请权限。创建管理器不请求权限、不扫描、不连接；缺少 CONNECT 授权时可用性为 `UNKNOWN`。真实操作会检查权限并抛 `BtException.MissingPermission`。

在 Activity 中保留 `RequestMultiplePermissions` 回调和 `onResume()` 重查，具体实现见 [MainActivity.kt](../../../android-app/src/MainActivity.kt)。`setContent` 中的组合方式（Android/Compose 环境）为：

```kotlin
val bt = remember { createBtManager(applicationContext) }
val client = remember(bt) { createHeadsetClient(applicationContext, bt) }
DisposableEffect(client, bt) {
    onDispose {
        client.close()
        bt.close()
    }
}
App(
    client = client,
    missingPermissions = missingPermissions.value,
    onRequestPermissions = {
        val missing = BtPermissions.missing(this)
        if (missing.isNotEmpty()) permissionRequest.launch(missing.toTypedArray())
        else missingPermissions.value = emptySet()
    },
)
```

对应导入来自 `ink.lipoly.app.sunrise.blueConnector.BtPermissions`、`ink.lipoly.app.sunrise.blueConnector.createBtManager`、`headset.createHeadsetClient` 和 `compose.App`。管理器使用 `applicationContext`；factory 使用注入的管理器，不再创建第二个管理器。不要在重组中创建控制器或启动连接。

[AppContent.kt](../compose/AppContent.kt) 持续收集 `client.state` 和 `client.events`，权限齐备后通过 `LaunchedEffect` 调用 `client.startAutoConnect()`。自定义界面可采用同一模式：

```kotlin
val state = client.state.collectAsState().value
LaunchedEffect(client) {
    client.events.collect { event -> handleHeadsetEvent(event) }
}
LaunchedEffect(client, missingPermissions.isEmpty()) {
    if (missingPermissions.isEmpty()) client.startAutoConnect()
}
```

这里 `client` 为 `HeadsetClient`，`handleHeadsetEvent` 是宿主自己的事件处理函数。晚订阅时读取 StateFlow 快照恢复状态，不依赖瞬时事件。

## 单耳机自动连接策略

系统蓝牙须开启，耳机须已作为 A2DP/HEADSET 音频设备连接；仅配对不等于已连接音频。候选来自 `bt.refreshConnectedAudioDevices()`，按地址去重，不按品牌排除设备：

- 0 台：`HeadsetPhase.IDLE`，等待后续音频连接事实。
- 1 台：尝试连接。
- 多台：`SELECTION_REQUIRED`，不擅自选择；在协程中调用 `discoverConnectedDevices()`，展示返回值后调用 `connect(device)`。
- 手动首次失败：向 `connect` 调用方抛原异常并停止该次目标循环；成功后的连接丢失会重试。

端点顺序为：记住的 BLE 地址 → 音频设备自身地址 → 已配对 LE/DUAL 同名设备 → LE 扫描同名/同地址结果。地址去重；扫描缺权限时仍保留直接 GATT 尝试。超时/传输错误不改写为“不支持”。同名只是一项关联策略，不能证明两地址属于同一物理耳机。

`HeadsetDevice.address` 始终是显示的音频身份，`name` 是信息快照；`verified` 仅表示记住过地址关联。实际通讯端点可能是不同 BLE 地址。READY 后写入原有 `drop_verified_devices` SharedPreferences，大写音频地址为 key、BLE 地址为 value；已有数据保留。

`HeadsetClient` 初次 READY 后读取 ANC、立即读取 GAIA 电量，并每 30 秒轮询电量。失败保留未知/最近读值。`disconnect()` 停止自动模式、释放本 facade 的控制器和尝试连接、重置状态；再次调用 `startAutoConnect()` 可恢复。`close()` 幂等取消自有任务，连接清理由任务的取消收尾完成；它不关闭注入的管理器，不影响其他设备的会话。Activity 最后再关闭管理器。

## 直接控制一个或多个设备

不需要应用的单耳机策略时，直接组合 `BtDevice` 和 `DropController`。控制器的构造、`awaitReady()`、`close()` 都不会连接或断开 GATT。构造可早于或晚于显式连接；同一管理器的不同设备各自有独立连接、队列和协议事务。

```kotlin
suspend fun readTwoDevices(bt: BtManager, addressA: String, addressB: String) {
    val a = bt.device(addressA)
    val b = bt.device(addressB)
    require(a !== b)
    val ca = DropController(a)
    val cb = DropController(b)
    try {
        a.gatt.connect()
        b.gatt.connect()
        ca.awaitReady()
        cb.awaitReady()
        require(DropProtocol.GAIA_BLE in ca.state.value.protocols)
        require(DropProtocol.GAIA_BLE in cb.state.value.protocols)
        coroutineScope {
            val batteryA = async { ca.gaia.getBattery() }
            val batteryB = async { cb.gaia.getBattery() }
            println("A=${batteryA.await()} B=${batteryB.await()}")
        }
        ca.close() // 只关闭 A 的控制器；A/B 的 GATT 仍由本函数拥有。
        if (DropProtocol.SOURCE_9ECA in cb.state.value.protocols) cb.source.ping()
    } finally {
        ca.close()
        cb.close()
        withContext(NonCancellable) {
            try { a.gatt.disconnect() } finally { b.gatt.disconnect() }
        }
    }
}
```

示例需 `ink.lipoly.app.sunrise.blueConnector.*`、`drop.*` 与 `kotlinx.coroutines.*` 导入，传入真实地址；GAIA/9ECA 命令仍须按实际协议检查，不能假定所有设备同时支持两协议。`awaitReady()` 在没有已连接会话时抛 `NotReady`。普通 GATT 服务发现成功即为 `CONNECTED`；没有 GAIA/9ECA 时控制器进入 `ERROR` 并抛 `UnsupportedDevice`，**不**关闭这个普通 GATT 连接。

`controller.gaia/source` 每次取当前 READY 绑定的控件；断连或换会话后旧引用立即失效，不要跨连接缓存控件对象。关闭控制器使其请求以 `Disconnected` 结束，但不会顺带断开 GATT 或关闭其他使用者的 CCCD。已经开始的原生读写仍排空 callback 后才允许下一项操作；未开始的已取消任务不会触发原生操作。

同一设备上重复创建控制器并行操作同一协议不在保证范围内；多控制器隔离保证针对不同设备。直接使用控制器时没有自动重连、扫描、品牌筛选或电量周期轮询。

## GAIA 与 9ECA 操作

公开方法签名保持在 [DropApi.kt](DropApi.kt)。GAIA 提供电量、ANC、增益、LED、空间音效/头部追踪、编解码、动态低音、左右反转、EQ、手势、基础信息、音频调节和关机；9ECA 提供音源、音量、预设/参数 EQ、麦克风增益、固件、设备信息和 ping。

所有设备方法是挂起函数。控制器调用先检查控制状态与协议；应用 facade 调用还要检查 `HeadsetPhase.READY`，不能在应用已重连时仅凭残留的 `controls.READY` 放行。

```kotlin
suspend fun useDrop(controller: DropController) {
    val state = controller.state.value
    if (state.phase != DropPhase.READY) return
    if (DropProtocol.GAIA_BLE in state.protocols) {
        controller.gaia.getBattery()
        if (AncMode.NOISE_CANCELLING in state.capabilities.ancModes) {
            controller.gaia.setAncMode(AncMode.NOISE_CANCELLING)
        }
    }
    if (DropProtocol.SOURCE_9ECA in state.protocols) {
        val entries = try {
            controller.source.readSourceCapability().entries
        } catch (e: DropException.UnsupportedCapability) {
            controller.source.getCapabilityPage(0).entries
        }
        entries.firstOrNull()?.let { controller.source.setAudioSource(it.sourceId) }
    }
}
```

9ECA 可用不保证 readable capability/info 特征存在。缺 capability 时 `readSourceCapability()` 抛 `UnsupportedCapability`，`getCapabilityPage(0)` 仍可独立读取并解析；没有音源条目时不调用 `setAudioSource`，不要猜 ID。固件优先 readable info，缺失或读取失败时可回退协议 GET_FW_VERSION。能力探测失败可令 `capabilities.complete=false`，但有可用协议仍可 READY；这不是“明确不支持所有功能”。

GAIA `requestRaw` 等待匹配回复；`sendRaw` 只等待传输写入，不表示设备已经执行或状态确认。ANC 一次 SET 后最多四次读回；无 SET ACK 也可通过读回确认。持续失配抛 `AncModeMismatch(requested, observed)` 并保留实际读值；读回无法验证抛 `Unverified`、清除 `ancMode`，不把发送成功当成确认成功。取消和断连不转为成功。

`SourceSwitchOptions.awaitStable=true` 默认等待/轮询稳定切换；`false` 只返回首次响应，不能据此推断已经稳定。`persistDefault`、`muteDuringSwitch`、`noBluetoothAutoResume` 默认关闭，`fadeSeconds` 默认 5；其他参数范围见 [SourceControlsImpl.kt](SourceControlsImpl.kt)。

## 状态、事件与错误

- `BtManager.availability/devices/connectedAudioDevices`：可用性、已取得句柄、音频连接事实。`device(address)` 只校验并返回规范大写 MAC 的稳定句柄，不扫描、不连接、不冒充发现；首次系统枚举/扫描观测到地址才发 `BtEvent.OnDiscovered(device, sender)`。这个事件不意味着 GATT 已连，也不意味着品牌或协议支持。
- `device.gatt.state`：该设备连接的唯一生命周期事实。每次服务发现产生属于特定会话的特征句柄；UUID 小写。旧会话特征不可用于新会话。
- `GattSession.events`：原始 `GattEvent.ValueChanged`，不解码协议。慢订阅造成容量 64 的通知队列溢出时明确报 Transport 并关闭会话，不静默丢包。
- `DropController.state`：`DropState`，仅 `IDLE/PROBING/READY/ERROR`；协议仅 `GAIA_BLE/SOURCE_9ECA`，没有应用连接设备字段。电量、ANC 等未知值为 `null`，UI 应显示“—”。
- `HeadsetClient.state`：`HeadsetState`，应用连接阶段、音频设备与连接错误独立于 `controls: DropState`。事件为 `HeadsetEvent.Control(DropEvent)` 或 `HeadsetEvent.Error(Exception)`。

始终先透传取消，再处理原错误：

```kotlin
suspend fun runHeadsetAction(client: HeadsetClient, onError: (Exception) -> Unit) {
    try {
        val state = client.state.value
        if (state.phase != HeadsetPhase.READY || state.controls.phase != DropPhase.READY) return
        if (DropProtocol.GAIA_BLE in state.controls.protocols) client.gaia.getBattery()
    } catch (e: CancellationException) {
        throw e
    } catch (e: BtException) {
        onError(e)
    } catch (e: DropException) {
        onError(e)
    }
}
```

连接层 `BtException.MissingPermission`、`BluetoothUnavailable`、`InvalidDevice` 分别用于授权、蓝牙不可用、无效地址；连接错误在 facade 中保留原型。控制层将 Bt Timeout/Disconnected 映射为对应 Drop 异常，UnsupportedOperation/InvalidGattHandle 映射为 UnsupportedCapability，PacketTooLarge 映射为 Protocol，其他 Bt 异常映射为 Transport 并保留 cause。

原生读写/descriptor 的期限为 10 秒、MTU 为 5 秒；失去原生 callback 时队列和该会话一起失败，避免晚 callback 被下一项误用。协议回复等待为 6 秒；单纯协议等待超时不关闭 GATT。`Unverified`、`AncModeMismatch`、`Rejected`、`Protocol` 不能被当作成功；以原型提示，不抹平 ANC 专属错误。

## 配置身份与平台限制

[DropProfile.kt](DropProfile.kt) 保留地址覆盖优先于名称、内置回退以及独立的写/读映射。只有经过固件实测的数字才应写进 `audioCurationWrite/Read`、`ancV2Write/Read` 或 `gainWrite`，不要从发送值推断读回值。

当音频地址与 BLE 地址不同，直接组合可用 `DropController(endpoint, options, profileDevice = audioDevice)`；`profileDevice` **只**提供地址/名称用于配置匹配，不扫描、不切换端点、不建立 GATT。Headset facade 已按这个方式传入原音频身份，不把关联机制放入 profile 表。

Android 的 [BtPlatform.android.kt](../../src@android/blueConnector/BtPlatform.android.kt) 将 `BtHost` actual 为 `Context`，提供真实平台实现。JVM 的 [BtPlatform.jvm.kt](../../src@jvm/blueConnector/BtPlatform.jvm.kt) 中 `createBtManager(object : BtHost() {})` 抛 `UnsupportedOperationException("Bluetooth is unavailable on JVM desktop")`，不创建假后端。[DesktopApp.kt](../../src@jvm/compose/DesktopApp.kt) 继续传 `client = null`；设置/诊断可访问，蓝牙动作不可执行。

本轮验证：Android/JVM 构建、两平台宿主各 45 项共享测试，以及独立 JVM 双控制器真实协议路径烟测（显式 fixture 通讯，不代表 Android 蓝牙）。实际 JVM 窗口已观察未知电量、平台不可用提示、禁用连接/控制按钮和设置/诊断导航。没有已授权 Android 真机与耳机；Android 无线 GATT、权限交互和多设备选择界面未实测。
