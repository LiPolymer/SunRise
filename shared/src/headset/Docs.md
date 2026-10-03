# headset：应用自有的单耳机组合层

`ink.lipoly.app.sunrise.headset` 把系统音频设备选择、GATT 连接、Drop 控制器和应用重试/轮询组合成一个长期存在的 `HeadsetClient`。它不是通用蓝牙管理器，也不是第二套协议 API。

## 导航

- [职责、依赖和资源所有权](#职责依赖和资源所有权)
- [源码入口](#源码入口)
- [Android 与 Compose 接入](#android-与-compose-接入)
- [公开 API 参考](#公开-api-参考)
- [状态、模型与事件参考](#状态模型与事件参考)
- [音频选择与 BLE 端点尝试](#音频选择与-ble-端点尝试)
- [重试、取消和轮询](#重试取消和轮询)
- [并发、epoch 和清理](#并发epoch-和清理)
- [完整手动读取示例](#完整手动读取示例)
- [异常与界面恢复](#异常与界面恢复)
- [常见误用与验证边界](#常见误用与验证边界)

## 职责、依赖和资源所有权

依赖方向是 `headset → blueConnector + drop`。通用蓝牙契约见 [blueConnector 文档](../blueConnector/Docs.md)，协议、能力和各控件的读写确认规则见 [drop 文档](../drop/Docs.md)。

| 所有者 | 职责 | 释放边界 |
|---|---|---|
| Android 宿主 | 权限交互、系统音频连接前提、一个 `BtManager`、一个客户端的 UI 生命周期 | 先释放客户端，再释放自己拥有的管理器 |
| `BtManager` | 稳定地址句柄、系统设备事实、通用 GATT | 由宿主关闭；客户端从不关闭注入的管理器 |
| `HeadsetClient` | 单音频身份选择、端点尝试、显式 GATT connect/disconnect、自己的 `DropController`、重试与初读/电量轮询 | 清理自己的尝试端点与观察任务；不清理其他设备 |
| `DropController` | 当前 GATT 会话中的协议初始化、请求匹配和功能状态 | 客户端关闭其控制器；控制器本身不拥有 GATT 连接 |

本包不做：品牌名单过滤、协议候选预筛选、系统音频 profile 强制连接、自动配对、RFCOMM、通用多耳机 UI、伪桌面蓝牙。自动模式依赖设备已经被系统当作音频设备连接；手动连接地址不替用户建立这个系统音频连接。

隔离保证针对**不同设备**。不要让另一个客户端/控制器同时拥有本客户端正在尝试的同一 GATT 端点；通用 `connect()` 可能复用该设备已有会话，本客户端随后 `disconnect()` 会影响共享该端点的使用者。注入同一管理器、操作不同设备则不表示所有资源都归本客户端。

## 源码入口

| 文件 | 阅读目的 |
|---|---|
| [HeadsetApi.kt](HeadsetApi.kt) | 阶段、音频身份快照、应用状态、事件、内部关联存储契约 |
| [HeadsetClient.kt](HeadsetClient.kt) | 选择循环、候选尝试、会话采纳、epoch、轮询和清理的实际实现 |
| [HeadsetPlatform.android.kt](../../src@android/headset/HeadsetPlatform.android.kt) | Android 工厂及已有 SharedPreferences 数据语义 |
| [MainActivity.kt](../../../android-app/src/MainActivity.kt) | 完整现行宿主：权限请求、`remember`、释放顺序、`onResume` 复核 |
| [Android App.kt](../../src@android/App.kt) | Android Compose 容器入口，接收可空客户端与权限状态 |
| [AppContent.kt](../composeLegacy/AppContent.kt) | 授权后启动自动模式、多个音频候选选择、每次控件动作前复核就绪 |
| [DropDiagnostics.kt](../composeLegacy/DropDiagnostics.kt) | 分开显示应用连接/控制状态、实际异常和 ANC 确认失败 |
| [HeadsetClientTest.kt](../../test/headset/HeadsetClientTest.kt) | 纯协程 fixture 的选择、取消、重连、其他设备所有权和旧帧隔离行为证据 |

包名使用 `blueConnector`，但公共 `Bt*`、`Gatt*` 与 `createBtManager` 名称不变。`HeadsetClient` 的 common 构造器是 **internal**；外部 Android 调用方使用 `createHeadsetClient(context, bt, options)`，不能把测试中的直接构造写成公共 common 用法。

## Android 与 Compose 接入

可以直接参考并复用 [当前 MainActivity](../../../android-app/src/MainActivity.kt) 的完整宿主实现，再通过 [当前 Android App](../../src@android/App.kt) 接入界面。下面给出同一模式的完整 Android Activity 示例：在已有 Android 应用模块中使用，并由宿主 Manifest 注册选用的 Activity、声明相应权限；不要与原 MainActivity 同时创建第二套客户端。本例复用现有 `App`，**自动启动 effect 已由 AppContent 拥有，不再添加第二个启动 effect**。

```kotlin
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import ink.lipoly.app.sunrise.blueConnector.BtPermissions
import ink.lipoly.app.sunrise.blueConnector.createBtManager
import ink.lipoly.app.sunrise.compose.App
import ink.lipoly.app.sunrise.headset.createHeadsetClient

class HeadsetHostActivity : ComponentActivity() {
    private val missingPermissions = mutableStateOf(emptySet<String>())
    private val permissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        missingPermissions.value = BtPermissions.missing(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        missingPermissions.value = BtPermissions.missing(this)
        setContent {
            val bt = remember { createBtManager(applicationContext) }
            val client = remember(bt) {
                createHeadsetClient(applicationContext, bt)
            }
            DisposableEffect(client, bt) {
                onDispose {
                    client.close()
                    bt.close() // 仅因为本 Activity 确实拥有该管理器。
                }
            }
            App(
                client = client,
                missingPermissions = missingPermissions.value,
                onRequestPermissions = {
                    val missing = BtPermissions.missing(this)
                    if (missing.isNotEmpty()) {
                        permissionRequest.launch(missing.toTypedArray())
                    } else {
                        missingPermissions.value = emptySet()
                    }
                },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        missingPermissions.value = BtPermissions.missing(this)
    }
}
```

现行宿主的每一步含义：

1. `onCreate` 用 `BtPermissions.missing(this)` 获取缺失权限集合。该工具只检查；不会发出权限请求。Manifest 声明及版本差异见 [blueConnector 权限说明](../blueConnector/Docs.md)。管理器/客户端创建也不会申请权限。
2. Compose 中 `remember { createBtManager(applicationContext) }` 创建一个管理器；`remember(bt) { createHeadsetClient(applicationContext, bt) }` 复用它。不要在每次重组或每次点击时重新创建它们。
3. `registerForActivityResult(RequestMultiplePermissions())` 回调重新运行 `BtPermissions.missing(this)`，不是仅凭某个返回布尔值推断全部权限已具备。请求按钮同样先复核当前集合，再请求缺失项。
4. `onResume` 再次检查权限，覆盖用户从系统设置回来的授权变化。Compose 将集合传入 `App`。
5. [AppContent](../composeLegacy/AppContent.kt) 的 `LaunchedEffect(client, missingPermissions.isEmpty())` 仅在客户端存在且权限集合为空时调用 `startAutoConnect()`；它另有事件收集与 `SELECTION_REQUIRED` 时刷新候选的 effect。工厂不隐式启动。
6. `DisposableEffect(client, bt)` 在 `onDispose` 中先 `client.close()`，再 `bt.close()`。此处宿主确实拥有管理器；若管理器由更长生命周期的外部对象注入，则只关闭客户端，由外部所有者决定何时关闭管理器。

注意：`startAutoConnect()` 每次调用都会替换当前连接，不是幂等的“确保已启动”。不能在任意重组中直接调用。权限状态也不是永久保证：真实操作仍会检查平台状态并可能失败。当前 effect 在权限不足时不启动新的自动循环，但**没有在权限撤销分支显式调用 `disconnect()`**；若产品要求撤销权限时立即终止应用连接，需要宿主制定该交互策略，不应误读为工厂已代劳。

Compose effect 的取消和客户端生命周期不同：`startAutoConnect()` 是非挂起方法，启动的是客户端自有任务，effect 返回/取消不等于连接被断开。要主动停止用 `disconnect()`，最终释放用 `close()`。

## 公开 API 参考

| API | 结果、前提和副作用 |
|---|---|
| `state: StateFlow<HeadsetState>` | 最新应用快照，有默认初值；晚订阅以它恢复当前状态 |
| `events: SharedFlow<HeadsetEvent>` | 无重放、额外缓冲 32、`tryEmit` 尽力通知；不是可靠历史/请求通道 |
| `gaia: GaiaControls` | 当前已采纳且会话仍有效的 READY 控件；getter 不保证 GAIA 或具体能力存在 |
| `source: SourceControls` | 同上，实际方法检查 9ECA 特征/协议支持 |
| `discoverConnectedDevices()` | 挂起刷新系统原始已连接音频列表并创建快照；不 LE 扫描、不改变当前选择、不建立 GATT |
| `startAutoConnect()` | 同步接受启动，异步替换旧选择并执行自动循环；不等待 READY，错误看状态/事件 |
| `connect(address: String)` | `bt.device(address)` 取得句柄与快照，再执行设备重载；地址校验在管理器，不要求已出现在音频候选中 |
| `connect(device: HeadsetDevice)` | 替换当前连接，等待首次有效 READY；返回后客户端继续拥有连接与丢失后的重试 |
| `disconnect()` | 停止本轮和自动模式、失效选择、重置状态，挂起等待不可取消清理完成；不删除已存关联，可再次显式启动 |
| `close()` | 幂等终结；同步失效/重置，不挂起等待 GATT 断开，资源在任务 finally 中继续清理；不可再次启动/连接/发现 |
| Android `createHeadsetClient(context, bt, options = DropOptions())` | 用 applicationContext 创建客户端与平台关联存储；不创建管理器、不扫描/配对/连接 |

`gaia`/`source` getter 同时要求：当前连接 epoch 未关闭、当前 attempt 已采纳、端点仍为 CONNECTED 且 session 对象匹配、应用状态 READY、转发控制状态 READY、控制器自身 READY。条件不成立抛 `DropException.NotReady`，**客户端 close 后 getter 也抛 NotReady**。与之不同：`startAutoConnect`、`connect`、`discoverConnectedDevices` 在客户端关闭后抛 `DropException.Disconnected`；先前已取得的旧控件在其控制器绑定失效后也抛 `Disconnected`。不要据此缓存旧控件“等它恢复”。

## 状态、模型与事件参考

### HeadsetPhase

| 阶段 | 解释 |
|---|---|
| `IDLE` | 默认、主动断开、无自动音频候选；close 也重置为此阶段，所以它不是关闭状态标记 |
| `DISCOVERING` | 自动刷新系统音频候选中 |
| `CONNECTING` | 首次连接尝试；即使自动首次失败后重试，也不一定进入 RECONNECTING |
| `PROBING` | 控制器初始化，或其 READY 尚未被应用层验证/采纳 |
| `READY` | 端点已被采纳，功能仍可能未知、缺失或部分探测不完整 |
| `RECONNECTING` | 曾就绪目标丢失会话，清理或重试中，不允许功能按钮继续使用残留状态 |
| `SELECTION_REQUIRED` | 自动发现多台原始音频设备，不擅自选择，即使仅一台有已记关联 |
| `ERROR` | 实际连接/探测失败的当前展示；是否重试取决于本轮策略，不是必然终态 |

### 字段与快照

| 模型/字段 | 含义 |
|---|---|
| `HeadsetDevice.device` | 所选音频身份的 `BtDevice` 稳定句柄；选择应来自注入的同一管理器，本客户端没有“跨管理器迁移”契约 |
| `HeadsetDevice.name` | 发现/构造时名称快照，可能为空；设备信息变化时客户端重建当前显示对象，先前返回列表中的旧对象不原地变化 |
| `HeadsetDevice.verified` | 仅有地址关联记录，不表示硬件认证、当前连接成功或协议能力确认 |
| `HeadsetDevice.address` | `device.address` 的只读 getter，仍是音频身份，不变为 BLE 端点地址 |
| `HeadsetState.phase` | 应用选择/连接阶段 |
| `HeadsetState.device` | 当前所选音频快照；无目标/多选时为空 |
| `HeadsetState.controls` | 原 `DropState`：协议阶段、协议集合、能力、电量、ANC、音源等；不再复制到应用模型中 |
| `HeadsetState.error` | 最近连接/初始化报告的 `Exception?`，保留实际类型；普通控件调用失败不保证自动写入这里 |

应用连接 READY 和控制 `DropPhase.READY` 是两个条件。控制器有至少一种协议即可 READY，`capabilities.complete == false` 仍可能可用，空能力集合也不能一律解释为全部不支持。未知电量/ANC 显示未知，不构造默认“成功”值。具体模型见 [drop 模型参考](../drop/Docs.md)。

### 事件

- `HeadsetEvent.Control(event: DropEvent)`：包装当前控制器的事件，保持原始通知载荷/错误子类型。控制层初始化错误可能以 `Control(DropEvent.Error(...))` 出现。
- `HeadsetEvent.Error(cause: Exception)`：连接循环报告的实际异常。`BtException` 不被统一改成 `DropException`；控制层的 ANC、协议/传输异常也不应在 UI 被泛化成“不支持”。

两者都通过 epoch/attempt 校验，旧控制器事件不得覆盖新选择。但是流本身不重放，额外缓冲是有限的，`tryEmit` 在慢订阅者使缓冲满时可能失败，因此事件会错过。没有订阅者也不保存历史。不要从事件计数推导状态，不要通过 `HeadsetEvent` 等待协议响应，错误历史日志需要 UI 自行收集保存。当前界面只保存近期事件，当前事实取 `state.value` 或收集 `state`。

## 音频选择与 BLE 端点尝试

### 自动选择只数原始音频候选

`discoverConnectedDevices()` 调用 `bt.refreshConnectedAudioDevices()`。它不根据品牌、名称、记住的关联或协议能力删掉候选：

- **0 台**：发布默认 IDLE，循环之后再次发现。
- **1 台**：尝试这台音频身份对应的控制端点。
- **多台**：发布 SELECTION_REQUIRED，不自动连接其中某台；UI 再调用发现方法得到列表，将用户选中的 `HeadsetDevice` 传给 `connect`。

候选并不保证支持 GAIA/9ECA。“显示在候选里”和“可以控制”是两个不同事实。

### 一次所选身份的尝试顺序

每个端点按地址大写去重，成功采纳则立即结束后续尝试：

1. **缓存端点**：`associations.endpoint(selected.address.uppercase())`。历史非法地址被忽略，不生成假端点。
2. **音频地址自身**：`selected.device` 直接 GATT。
3. **已配对同名端点**：仅当所选名称非空白，遍历 `bt.bondedDevices()`，接受 kind 为 LE/DUAL 且名称忽略大小写相等的设备。
4. **LE 扫描结果**：`bt.scanLe(name = selected.name, address = selected.address)`，按蓝牙层名称或地址匹配语义处理；没有硬编码 BLE 地址。若名字未知，不能期望发现另一个不同地址的同名 BLE 端点。

没有 RFCOMM 最终回退；同名关联只是应用策略，**不能证明同一物理耳机**。扫描缺权限仅跳过扫描阶段，不否定已经尝试的缓存/直接 GATT。其他枚举、扫描、连接和探测异常保留为真实失败。

每次尝试创建 `DropController(endpoint, options, profileDevice = selected.device)`，客户端自己调用 `endpoint.gatt.connect()`，再 `controller.awaitReady()`。因此：

- 控制器不会自动连接；无线连接归客户端的 attempt。
- profile 地址/名称匹配使用音频身份；BLE 地址不同不会悄悄改变用户配置匹配对象。
- 音频身份与 BLE 端点地址不同时，其经典 RFCOMM 句柄随控制器转交结构化 EQ 写入（见 [drop 传输选择](../drop/Docs.md)）；同地址时不猜测对端提供 SPP 记录。
- 控制器 PROBING/READY 状态可能先到达；在当前 session 仍有效、控制器确实 READY、成功写入关联并标记采纳后，才公开应用 READY。
- 失败/取消先释放该次控制器和 GATT，再尝试下一候选，不留下静默后台连接。

### 关联持久化

Android 工厂沿用私有 SharedPreferences **`drop_verified_devices`**：key 是大写音频地址，value 是大写端点地址，保留旧记录。只在成功采纳前的 READY 校验通过后保存；`apply()` 更新内存并异步写磁盘，不声称 `connect()` 返回意味着磁盘已经刷写完成。

`verified=true` 仅表示记录存在。陈旧记录先试、失败后继续真实候选；本包没有额外的关联删除/认证 API。`disconnect()` 和 `close()` 也不删除记录。成功后显示仍是音频地址/名称，端点只用于控制通讯。

## 重试、取消和轮询

| 情形 | 当前策略 |
|---|---|
| 手动 `connect` 首次成功前失败 | 结束本轮，保留 ERROR，向等待的调用方抛实际异常；不会无请求地继续首连重试 |
| 手动首次等待被调用方取消 | 清理本轮后原样抛 `CancellationException`；不会把取消当成功/普通错误 |
| 本轮被另一选择、disconnect 或 close 替换，尚未 READY | 尚未完成的首次等待失败为 Disconnected；原控制器/端点清理后允许新选择运行 |
| 手动连接已经 READY 后丢失 GATT | 客户端自有循环继续重连；先清空旧功能状态，恢复时使用新控制器/会话 |
| 自动首次连接失败 | 报告失败后仍可重试；目标必须仍出现在系统已连接音频事实中 |
| 自动目标的音频地址消失 | 观察 `connectedAudioDevices` 取消目标循环并释放资源，回到发现；无需等慢连接成功才识别目标离开 |
| 主动 `disconnect()` | 停止自动模式；蓝牙事件不会擅自重启，需要显式 `startAutoConnect()` 或 `connect()` |

发现循环等待上限 **5 秒**，端点失败/丢失后的重试等待上限 **4 秒**；管理器事件通过合并 wake channel 可提前唤醒。等待前会丢弃刚结束尝试产生的合并通知，避免自己导致立刻重试。这些值是等待策略，不是包含系统枚举/连接/探测耗时的完整周期，也不代表无线调用的超时；底层超时见 [blueConnector](../blueConnector/Docs.md) 与 [drop](../drop/Docs.md)。

每次采纳后启动两个独立任务：能力集合有 ANC 模式时尝试 GAIA ANC 初读；含 GAIA_BLE 协议时立即查询电量，之后每次读取结束再等待 **30 秒**。READY/`connect()` 返回不保证初读完成，9ECA-only 也不被保证具有 GAIA 电量。初读/轮询的普通异常被忽略，保留未知或最近真实读值；调用方取消仍先透传，不生成电量/模式默认成功值。

`connect()` 返回后连接属于客户端自有 scope，不再依赖原调用方 Job。取消一个 UI 控件读写也不等价于停止整个客户端连接；要停止生命周期请调用 `disconnect()`。

## 并发、epoch 和清理

客户端的 scope 使用 `SupervisorJob + Dispatchers.Default`。`lifecycle` Mutex 只保护短的替换、采纳、发布、资源标记操作；不持锁等待 GATT、probe 或前驱清理。

- **连接 epoch** 是 `Connection` 对象身份，不是地址/阶段字符串。重复选择同地址也产生新 epoch。
- **attempt 身份** 是当前端点尝试对象；状态/事件须同时属于当前 Connection 与 Attempt。
- **session 身份** 用端点 `gatt.state` 的 CONNECTED 和 session 对象引用校验。旧帧、旧控件、旧关闭结果不能发布成新选择状态。
- 新 Connection 在锁外等待前驱 `finished`，前驱即使在进入无线工作前已被取消，也必须走完 cleanup 链。`UNDISPATCHED` 入口确保替换和 launch 之间发生 close 时仍安装 finally。
- `ownsGatt` 在通用 `connect()` 挂起**之前**设置。即使客户端尚未拿到返回的 session，清理也会显式 `endpoint.gatt.disconnect()`：只取消 connect 的等待者不足以取消管理器自有的无线连接任务。
- 清理运行于 `NonCancellable`：先使 attempt 失效并关闭控制器/观察任务，锁外断开端点。旧清理只释放自己的资源，不写新 epoch 的状态。
- `close()` 无需等待 lifecycle 锁即可同步设置关闭标记；发布路径最后再次检查关闭标记，防止竞态将非默认状态写回。

**`disconnect()` 与 `close()` 不等价**：前者挂起等待当前连接清理完成，可随后重启；后者同步终结并取消自有 scope，返回时 GATT 清理可能仍在进行。close 后观察到 IDLE 也不是“所有原生句柄已经断开”的证明。需要确定本轮释放完成时，先挂起 `disconnect()`，再最终 `close()`；宿主若拥有管理器，最后再 `bt.close()`。不要因本客户端 close 而关闭外部所有者的管理器或另一设备。

## 完整手动读取示例

下面是应用层可复用函数，前提是调用方已通过 Android 工厂创建长期客户端并完成授权，`selected` 来自该客户端的 `discoverConnectedDevices()` 或同管理器句柄。它明确替换已有选择，读取后停止本轮；若 UI 想保留连接，应把 `disconnect()` 放在实际生命周期结束处，而不是照搬成每次按钮动作。

```kotlin
import ink.lipoly.app.sunrise.drop.DropException
import ink.lipoly.app.sunrise.drop.DropPhase
import ink.lipoly.app.sunrise.drop.DropProtocol
import ink.lipoly.app.sunrise.drop.EarbudBattery
import ink.lipoly.app.sunrise.headset.HeadsetClient
import ink.lipoly.app.sunrise.headset.HeadsetDevice
import ink.lipoly.app.sunrise.headset.HeadsetPhase
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

suspend fun readSelectedBattery(
    client: HeadsetClient,
    selected: HeadsetDevice,
): EarbudBattery {
    try {
        client.connect(selected) // 等本轮有效 READY；不代表初始电量轮询已完成。
        val snapshot = client.state.value
        if (snapshot.phase != HeadsetPhase.READY ||
            snapshot.controls.phase != DropPhase.READY
        ) throw DropException.NotReady()
        if (DropProtocol.GAIA_BLE !in snapshot.controls.protocols) {
            throw DropException.UnsupportedCapability("GAIA battery")
        }
        // 不缓存跨会话控件；返回的是本次实际查询值，不将 READY 推断成已知电量。
        return client.gaia.getBattery()
    } finally {
        // 等待本函数选择的生命周期释放，取消时也清理；外部仍拥有 client 与 bt。
        withContext(NonCancellable) { client.disconnect() }
    }
}
```

此函数应由负责该客户端选择的单一应用流程调用；不要与另一个同时替换选择的流程并用，否则 finally 的 `disconnect()` 会停止客户端当时的当前选择。它不接管客户端/管理器的最终 close。调用方展示电量时继续处理 `null` 为未知；范围和设备原始字节规则见 [drop](../drop/Docs.md)。

需要长期交互时，按 [AppContent 的 `runAction`](../composeLegacy/AppContent.kt) 模式：开始动作前和动作协程内再次检查应用与协议 READY，在协程中每次取 `client.gaia`/`client.source`，`CancellationException` 先抛回，再处理具体异常；不是只依赖按钮禁用状态。音源 ID 必须从真实能力页面/状态取得，具体分页和可用项选择例子见 [drop](../drop/Docs.md)，不要硬编码某型号的 ID。

## 异常与界面恢复

- 地址/枚举/直接连接失败保留 `BtException`，例如 `InvalidDevice`、`MissingPermission`、`Timeout`。工厂和 `verified` 标记不能绕过权限。
- 控制探测和功能操作遵循 `DropException`。控制层封装底层错误的具体映射见 [drop 异常参考](../drop/Docs.md)；facade 不再统一改写异常。
- 端点全部失败时，返回最后记录的真实非 UnsupportedDevice 异常；没有这类失败才返回候选的 `UnsupportedDevice`，若没有任何记录则回退 `Disconnected`。因此一次普通连接超时不能被展示成“确定协议不支持”。扫描 MissingPermission 被特意跳过，历史缓存的 InvalidDevice 被忽略，并不成为端点支持证明。
- `DropException.Unverified` 表示 ANC 已发送但读回无法确认，控制状态 `ancMode` 为未知；`AncModeMismatch` 保留 requested/observed，状态保留实际观察模式。不要显示成成功，也不要丢失专属异常载荷。当前 [诊断页](../composeLegacy/DropDiagnostics.kt) 单独展示这两类情况。
- 普通控件异常直接抛给操作调用方，不保证更新 `state.error` 或发 `HeadsetEvent.Error`。初读/轮询失败不会自动弹出错误通知。UI 应按操作返回/异常给反馈，而非把 ERROR 当作所有失败的唯一入口。
- 状态流是当前事实而非不可变成功承诺：读 snapshot 后可能立即断连，后续 getter/操作仍可能失败。重连/切换清空协议功能值；UI 必须同时检查应用阶段，不能在 RECONNECTING 时根据旧 READY 控件数据放行。

## 常见误用与验证边界

- **看到多台候选只留下熟悉品牌**：当前包刻意不筛选。用户选择和探测结果才决定本轮是否可用。
- **关联有勾就表示耳机已连接**：勾只代表缓存关联，可能陈旧；真实可用性看当前 READY 和实际操作。
- **相同名称等于同一物理设备**：不是。现有候选策略不提供硬件身份认证。
- **构造客户端就会连接**：工厂只创建对象；授权后显式 start/connect。
- **close 返回等于已完成 GATT 断开**：不是。close 同步失效、异步清理；disconnect 才等待当前轮次清理。
- **关掉注入管理器以停止轮询**：不应。客户端自己取消轮询；外部管理器还可能服务其他设备。
- **getter READY 就能调用所有功能**：不是。协议、能力/特征与实际错误仍须检查，详见 drop。
- **缓存控件跨重连使用**：旧引用按旧绑定失败，不会动态转向新会话；重新取 getter。
- **把事件流当可靠日志或协议回复**：它是有限缓冲的尽力通知，snapshot 和操作结果才是界面事实来源。

本指南依据当前生产实现、宿主调用和 [现有 fixture 测试源码](../../test/headset/HeadsetClientTest.kt) 描述契约。fixture 覆盖原始 0/1/多候选、不同品牌保留、音频/BLE 身份分离、初次手动失败、取消未返回的连接、切换/旧帧隔离、重连、名称快照与其他设备不受 close 影响；它不代表 Android 无线或 SharedPreferences 落盘时序的实体证明。

Android 真正枚举、授权、扫描、GATT、系统音频事实，以及真实耳机协议/电量/ANC 需要相应硬件与权限验证。本包的 Android 工厂没有 JVM 同等工厂；桌面 `createBtManager` 明确不可用，现有桌面/Preview 使用可空客户端路径而不创建假蓝牙后端。阅读或编辑文档不能声称完成这些硬件验证。
