# blueConnector：通用蓝牙与设备级 GATT 参考

本包的完整包名为 `ink.lipoly.app.sunrise.blueConnector`。公开类型仍叫 `BtManager`、`BtDevice`、`GattSession` 等，工厂仍是 `createBtManager`；包名不是协议名，也不代表任何品牌。

## 导航

- [职责、依赖与资源所有权](#职责依赖与资源所有权)
- [源码索引](#源码索引)
- [平台入口与 Android 权限](#平台入口与-android-权限)
- [设备身份与发现](#设备身份与发现)
- [公开 API 与数据模型](#公开-api-与数据模型)
- [状态与事件](#状态与事件)
- [连接、操作与取消](#连接操作与取消)
- [错误参考](#错误参考)
- [完整使用示例](#完整使用示例)
- [常见误区与验证边界](#常见误区与验证边界)

## 职责、依赖与资源所有权

本包提供平台无关的蓝牙契约和 Android 的真实实现：取得设备句柄、枚举系统音频连接/已配对设备、扫描 LE、请求配对、显式建立设备级 GATT、服务发现、原始读写、通知和 MTU 协商。它只提供 GATT 通讯，不提供 RFCOMM、协议解码、品牌过滤、自动选择、重连、轮询、偏好持久化或音频地址到 LE 地址的关联。

公共契约只使用 Kotlin/协程 Flow，不暴露 Android 类型；Android actual 使用系统公开 API。JVM actual 不提供蓝牙后端。`blueConnector` 不依赖 Drop、Headset 或 Compose：

| 层 | 职责 | 文档 |
| --- | --- | --- |
| blueConnector | 通用通讯事实与连接资源 | 本页 |
| Drop | 使用既有设备的 GATT 会话，探测并执行控制协议 | [Drop 参考](../drop/Docs.md) |
| Headset | 应用自有的单耳机候选、地址关联、显式连接、重试及轮询策略 | [Headset 参考](../headset/Docs.md) |

资源树为 `BtManager → 每地址 BtDevice → 该设备 BtGatt → 当前 GattSession`。管理器拥有广播接收器和后台作用域；每个会话拥有原生 `BluetoothGatt`、操作队列与通知任务。调用方创建管理器后必须最终 `close()`。注入管理器的组件不应擅自关闭它；先释放组件自有会话，再由管理器所有者关闭管理器。

不同设备的连接、队列、pending 原生操作和通知通道彼此独立；关闭 A 会话不关闭 B 会话。**同一设备只提供一个当前 GATT 会话**：重复 `connect()` 复用它，不会为每个调用者创建独立连接；同一会话的通知开关也不是引用计数。多个组件共享同一设备时必须约定谁能断开、关闭或修改 CCCD，不能把“不同设备隔离”理解为同设备消费者隔离。

## 源码索引

相对链接从本文件所在目录出发：

| 源文件 | 阅读重点 |
| --- | --- |
| [BtApi.kt](BtApi.kt) | 全部公开模型、接口、事件和错误 |
| [BtPlatform.kt](BtPlatform.kt) | expect 宿主及工厂 |
| [GattOperationQueue.kt](GattOperationQueue.kt) | 单 worker、容量 64、等待者取消和关闭竞态 |
| [BtPlatform.android.kt](../../src@android/blueConnector/BtPlatform.android.kt) | Context actual 与 applicationContext 工厂 |
| [BtPermissions.kt](../../src@android/blueConnector/BtPermissions.kt) | 按版本和操作类别检查完整权限名 |
| [AndroidBtManager.kt](../../src@android/blueConnector/AndroidBtManager.kt) | 身份缓存、实际观测、广播、权限与关闭 |
| [AndroidBtDevice.kt](../../src@android/blueConnector/AndroidBtDevice.kt) | 设备信息和公开配对请求 |
| [AndroidBtDiscovery.kt](../../src@android/blueConnector/AndroidBtDiscovery.kt) | A2DP/HEADSET、串行扫描、OR 匹配、错误与停止 |
| [AndroidBtGatt.kt](../../src@android/blueConnector/AndroidBtGatt.kt) | 共享连接任务、显式断开、旧会话隔离 |
| [AndroidGattSession.kt](../../src@android/blueConnector/AndroidGattSession.kt) | 服务/句柄、回调匹配、原生期限、通知缓冲 |
| [BtPlatform.jvm.kt](../../src@jvm/blueConnector/BtPlatform.jvm.kt) | 桌面明确抛不可用异常 |

行为证据入口：[操作队列测试](../../test/blueConnector/GattOperationQueueTest.kt)、[JVM 工厂测试](../../test@jvm/blueConnector/BtPlatformTest.kt)、[测试通讯 fixture](../../test/support/FakeBluetooth.kt)。这些是源码/纯协程行为证据，不是 Android 无线或实体设备兼容性证明；fixture 不属于生产后端。

## 平台入口与 Android 权限

公共入口为 `createBtManager(host: BtHost): BtManager`：

- Android：`BtHost` 是 `android.content.Context` 的 actual typealias。工厂使用 `host.applicationContext`，创建管理器和系统广播订阅，不申请权限，不自动连接或扫描。广播注册失败抛 `BtException.Transport`。
- JVM：`BtHost` 是抽象标记类。即使提供其子类，工厂也始终抛 `UnsupportedOperationException("Bluetooth is unavailable on JVM desktop")`；没有空实现或模拟成功路径。

Android 宿主负责 Manifest 声明、运行时权限请求、授权结果与重新进入前台后的检查。本包的 `BtPermissions.missing(context, scan = true)` **只检查，不弹授权界面**：

| 系统版本 | `scan = false` | `scan = true` |
| --- | --- | --- |
| API 31+ | `android.permission.BLUETOOTH_CONNECT` | CONNECT 与 `android.permission.BLUETOOTH_SCAN` |
| 较低版本 | 此 helper 不要求运行时权限 | `android.permission.ACCESS_FINE_LOCATION` |

`missing` 返回当前未授予的完整权限名集合。空集合不证明适配器开启、旧系统定位开关/扫描环境合适或操作一定成功；旧系统 Manifest 基础蓝牙权限也仍由应用配置。运行过程中权限可被撤销，每次真正无线操作都会重新检查。平台 `SecurityException` 会重新检查权限；复查未捕获竞态时使用本操作类别的完整所需权限集合，无法归为权限的拒绝则保留为传输错误。

初始 `availability` 的读取次序是：管理器关闭→`CLOSED`；没有适配器→`UNAVAILABLE`；缺 CONNECT 检查权限→`UNKNOWN`；能读取开启状态→`ENABLED`/`DISABLED`；读取遭 `SecurityException`→`UNKNOWN`。不要将 `UNKNOWN` 解释为设备不存在，也不要把 `ENABLED` 当成扫描授权。权限授予后状态并非由 helper 自动刷新；后续操作/广播刷新真实事实。

## 设备身份与发现

### 句柄不是观测结果

`device(address)` 将地址转大写、验证 Android MAC 格式并在本管理器内缓存稳定句柄。不裁剪空白，不扫描、不连接，也不发布 `OnDiscovered`。初始 `info` 可以是名称为空、类型 UNKNOWN、配对 NONE 的默认信息；只有后续实际平台观测才更新。此入口可用于调用方已经可靠取得的地址，无需先扫描，但获取句柄并不证明目标存在或支持 GATT。

`devices` 是所有已取得句柄的地址排序快照，包含手动取得但尚未观测的句柄。它不是“附近设备列表”。Android 另有 `observed` 地址集合：第一次被枚举、匹配扫描结果或广播实际观测时发布 `OnDiscovered`；以后信息变化才发布 `OnDeviceChanged`。首次观测前已调用过 `device()` 也不影响首次发现事件。同名不同地址不合并，设备类型不证明两个地址属于同一物理设备。

### 三种发现入口

| 方法 | 行为与边界 |
| --- | --- |
| `refreshConnectedAudioDevices()` | 查询 A2DP 和 HEADSET，按地址去重排序，更新 `connectedAudioDevices` 和设备信息；不做品牌/协议筛选，不连接 GATT |
| `bondedDevices()` | 返回系统已配对列表并观测，按地址排序；不创建配对，不推断音频/LE 地址关联 |
| `scanLe(name, address, timeoutMillis)` | 扫描匹配 LE 结果，按地址去重排序；不自动建立连接 |

音频 profile 顺序查询，单个代理最多等待 1,500 毫秒；代理请求返回 false、服务断开或期限到达时该 profile 贡献空列表。晚到的代理回调仍负责关闭代理。枚举本身是尽力快照，空列表不等同于所有系统音频连接均已可靠确认不存在。音频刷新有独立 Mutex。

扫描 `timeoutMillis` 默认 8,000 毫秒，必须大于零；期限从实际开始扫描计，不包括等本管理器扫描锁的时间。无两个过滤参数时接受全部结果；否则是**名称匹配或地址匹配**，两者都忽略大小写。名称匹配是完整名称匹配，不是子串。指定地址命中可提前停止；其他情况下到期限正常返回已找到列表，没有匹配返回空列表。名称读取优先系统名称，再回退广播名称。被过滤的结果不会进入管理器的观测缓存。

同一管理器的扫描串行化，不锁住任何设备的 GATT。scanner 为 null 时保留返回空列表的行为；`onScanFailed(errorCode)` 抛 `Transport`，不是空结果。扫描回调 channel 容量 64，溢出也抛 `Transport`。回调失败会被保存，即使已经收到目标地址，也不能用提前结束掩盖该失败。取消原样传播，`finally` 尽力 `stopScan` 并移除活动请求。

### 广播与配对

管理器观察系统无线状态、ACL 连接/断开、设备名称/配对状态、A2DP 和 HEADSET 连接状态广播。在 IO 自有作用域中刷新事实，广播回调不等待扫描/连接。**经典 ACL 断开不会直接判定 GATT 已断开**；GATT 以自身 callback 为准。无线关闭或刷新成非 ENABLED 状态会清空音频快照并失效所属 GATT/活动发现，但缓存句柄保留。

`requestBond()` 只调用公开 `BluetoothDevice.createBond()`。Boolean 仅表示系统是否接受请求，不表示配对完成；之后看 `device.info.value.bondState` 的广播更新。没有隐藏解绑、强制 profile 连接或自动授权逻辑。

## 公开 API 与数据模型

### BtManager 与 BtDevice

| 成员 | 类型/返回 | 使用含义 |
| --- | --- | --- |
| `availability` | `StateFlow<BtAvailability>` | 管理器可观察无线状态 |
| `devices` | `StateFlow<List<BtDevice>>` | 已取得稳定句柄，不承诺已发现/连接 |
| `connectedAudioDevices` | `StateFlow<List<BtDevice>>` | 最近音频枚举快照 |
| `events` | `SharedFlow<BtEvent>` | 无重放的管理器事件 |
| `device(address)` | `BtDevice` | 同管理器同规范地址返回同一身份 |
| `refreshConnectedAudioDevices()` | 挂起，`List<BtDevice>` | 更新音频/观测事实 |
| `bondedDevices()` | 挂起，`List<BtDevice>` | 系统已配对列表 |
| `scanLe(...)` | 挂起，`List<BtDevice>` | 匹配扫描结果 |
| `close()` | 同步，`Unit` | 幂等关闭整个管理器资源树 |

`BtDevice.manager` 是拥有者；`address` 是 Android 大写 MAC；`info` 是最近观测快照；`gatt` 是唯一设备连接入口；`requestBond()` 返回请求接受与否。`BtDeviceInfo` 字段：`name: String?` 默认 null；`kind: BtDeviceKind` 默认 UNKNOWN（UNKNOWN/CLASSIC/LE/DUAL）；`bondState: BtBondState` 默认 NONE（NONE/BONDING/BONDED）。这些不是品牌、协议或连接判据。

### BtGatt、GattSession 与句柄

`BtGatt.state` 是连接事实。`connect(): GattSession` 为挂起入口，`disconnect(): Unit` 是挂起资源终止入口。每次新会话的 `GattSession` 字段为：

| 字段 | 含义 |
| --- | --- |
| `id: Long` | Android 进程内递增会话标识，不持久化为设备身份 |
| `device: BtDevice` | 所属设备 |
| `services: List<GattService>` | 服务发现后固定快照，允许为空 |
| `mtu: StateFlow<Int>` | 当前 ATT MTU，字节数，初始 23 |
| `events: SharedFlow<GattEvent>` | 当前会话原始通知/指示，无历史重放 |

`GattService.uuid` 是服务 UUID，`characteristics` 是本会话句柄列表。`GattCharacteristic` 有 `serviceUuid`、`uuid` 和 `properties: Set<GattProperty>`；Android UUID 规范为完整小写字符串，映射 READ、WRITE、WRITE_NO_RESPONSE、NOTIFY、INDICATE 五类属性。**UUID 只用于查找，不是句柄所有权**：必须从当前 `session.services` 取句柄，不能自实现接口代替，不可把旧会话/其他会话句柄用于新会话。重连后重新查询，即使 UUID 一样。

| 会话方法 | 成功含义 | 检查/限制 |
| --- | --- | --- |
| `read(characteristic): ByteArray` | 原生成功读回调的独立字节副本 | 要 READ；不是通知缓存 |
| `write(characteristic, value)` | 带响应原生写回调成功 | 要 WRITE；WRITE_NO_RESPONSE 单独不足；单包长度 ≤ MTU−3；不分片、不等上层协议 ACK |
| `setNotifications(characteristic, enabled)` | 本地开关设置及 CCCD 写回调成功 | 要 NOTIFY 或 INDICATE 且有 CCCD；两者皆有时 NOTIFY 优先；false 写禁用；无引用计数、失败不保证回滚本地开关 |
| `requestMtu(value = 247): Int` | 返回并发布实际协商值 | 请求范围 23..517，返回值不保证等于请求值 |
| `close()` | 同步标记失效并尽力释放资源 | 幂等，不关闭管理器，不影响其他设备 |

写入使用 `WRITE_TYPE_DEFAULT`（带响应）。入队前复制调用方负载，入队前和开始执行时均检查 MTU−3。API 33+ 使用显式 byte array 的写入/描述符 API 和状态码判断；API 24–32 使用旧 value/writeType 加 Boolean API。读与通知兼容旧回调和 API 33+ 传值回调，复制平台可能复用的数组。本包不自动把长包切片，也不把 GATT 层写成功解释为某个设备设置已经生效。

## 状态与事件

### 无线可用性

| BtAvailability | 含义 |
| --- | --- |
| UNKNOWN | 无法确认，通常缺 CONNECT 权限/读取受限 |
| UNAVAILABLE | 没有适配器 |
| DISABLED | 适配器关闭 |
| ENABLED | 可以观察到适配器开启；具体操作仍重新检查 |
| CLOSED | 管理器永久关闭 |

### GATT 状态

`GattState(phase = DISCONNECTED, session = null, error = null)` 的三个字段分别是阶段、就绪会话、失败原因：

| GattPhase | 会话/错误 | 含义 |
| --- | --- | --- |
| DISCONNECTED | 通常均为空 | 初始、主动断开或普通断连；管理器可用时允许再连接 |
| CONNECTING | 均为空 | 原生连接或服务发现中 |
| CONNECTED | 携带当前 session | 服务发现完成，**不是上层协议 READY** |
| ERROR | session 空，error 为真实 BtException | 非普通断连失败；可由所有者决定后续动作 |
| CLOSED | 通常均为空 | 管理器关闭后的终态 |

典型路径为 `DISCONNECTED → CONNECTING → CONNECTED`；连接失败/原生期限/通知溢出可进入 ERROR；普通断连、session.close 或 disconnect 回到 DISCONNECTED；管理器关闭进入 CLOSED。状态流是晚订阅者恢复事实的入口；不能依赖之前已经发过的事件。

### 瞬时事件

| 事件 | 字段 | 含义 |
| --- | --- | --- |
| `BtEvent.OnDiscovered` | device、sender | 此管理器首次实际观测到地址；不保证已连接/支持协议 |
| `OnDeviceChanged` | device、sender | 已观测信息改变，读取 device.info |
| `OnBluetoothStateChanged` | availability、sender | 无线可观察状态变化 |
| `OnGattStateChanged` | device、state、sender | 某设备连接快照改变 |
| `OnError` | cause、sender、device（可空） | 广播后台失败或 GATT 错误；不保证每个直接调用异常都另外发事件 |
| `GattEvent.ValueChanged` | characteristic、value | 当前会话的原始特征通知/指示，不解码、不拼包 |

两个 SharedFlow 都不重放。管理器事件通过自有**无界** channel 串行发布，再使用有额外缓冲的 SharedFlow；不要把它与会话的容量 64 原始通知 channel 混淆。关闭时最终无线事件使用 tryEmit，随后取消作用域，不保证送达；读 availability/state 快照更可靠。无收集者时 SharedFlow 不保留历史，容量 64 的通知 channel 也不是给晚订阅者存档。

通知回调只复制并投递，不启动一个无限新任务。慢收集者使会话发布协程挂起，回调 channel 满时明确 `Transport("GATT notification buffer overflow")` 并关闭会话，防止静默丢协议帧。收集者应快速完成原始处理；额外缓冲/持久化属于使用者策略。事件 byte array 会共享给收集者，不应原地修改；如要可变处理请自行复制。

## 连接、操作与取消

Android 使用 `connectGatt(..., autoConnect = false, ..., TRANSPORT_LE)`，连接与服务发现各 12 秒。服务发现后便可发布 CONNECTED，不要求任何应用服务/特征、不自动订阅或协商 MTU。普通 GATT 外设可以连接成功后由上层判定协议不适用；是否断开由资源所有者决定。

同设备重复 connect 复用有效会话或同一进行中的 deferred。无线任务属于管理器作用域，不属于单次等待者。生命周期 monitor 仅保护创建/替换/分离，不能覆盖无线等待。disconnect 先在锁内分离、失败化共享结果，再锁外取消无线任务并 join；替代 connect 可以在旧任务取消完成前继续。旧会话/旧 callback 的对象身份检查防止旧结果改变替代会话。

### 两种取消必须区分

| 操作 | 调用者取消 | 资源关闭/断开 |
| --- | --- | --- |
| `connect()` | 只取消这次等待；无线尝试仍可完成并建立会话 | disconnect/session.close/manager.close 使尝试失败 |
| 已入队但未开始的读写 | 取消结果，worker 跳过 | 全部排队等待失败 |
| 已开始的原生读写/CCCD/MTU | 调用者立即取消等待；worker 继续排空本次原生回调/期限，才运行下一项 | 活动结果及 pending 失败，worker 停止 |
| LE 扫描 | 原样传播取消，finally 尽力停扫描 | 活动扫描 channel 失败 |

每会话有一个 worker、容量 64 的待执行请求 channel 及至多一个活动原生 pending；队列满时提交者挂起，不代表无线可以并行。原生回调同时匹配原生 GATT 对象、操作类型与特征/描述符身份，不能用相同 UUID 的其他句柄完成请求。

读、带响应写、CCCD 描述符写各 10 秒；MTU 为 5 秒。这些是原生操作开始后的自有期限，不含排队时间。内部 `withTimeoutOrNull` 将自己的期限转为 `BtException.Timeout`，不是把调用者取消改成超时。原生操作丢失回调后连接无法安全进入下一项，所以**超时终止整个会话与队列**，拒绝晚回调。普通提交拒绝/非成功回调不统一假装断开；处理真实错误和当前状态。

`session.close()` 同步标记失效、失败化连接/发现/pending、关闭队列、取消通知发布和会话 scope，然后尽力 disconnect/close 原生资源。只有它仍是设备的当前会话才改变设备状态。关闭期间即使无线/权限已不可用，也尽力释放原生对象。`manager.close()` 幂等注销广播、停止发现和自有任务、关闭全部设备会话并发布 CLOSED；不解绑设备，不关闭系统蓝牙。devices/info 缓存仍可被读取，后续无线操作不可继续。

## 错误参考

| BtException 子类 | 字段/原因 | 调用方解读 |
| --- | --- | --- |
| BluetoothUnavailable | 无适配器或无线关闭 | 检查系统环境，不当作协议不支持 |
| MissingPermission | `permissions: Set<String>` 完整权限名 | 交给宿主授权流程，不在本层请求 |
| InvalidDevice | message 给出无效地址 | 修正来源/格式，不靠扫描重试掩盖 |
| Disconnected | 资源失效/已关闭 | 当前会话与句柄引用生命周期已结束 |
| Timeout | `operation: String` | 内部期限；原生 GATT 操作期限到达会使会话失效 |
| UnsupportedOperation | `operation: String` | 缺所需属性或 CCCD，不代表整个设备不支持 GATT |
| InvalidGattHandle | 错误实现或会话归属 | 从当前服务快照重新取得句柄，不跨连接缓存 |
| PacketTooLarge | 长度 > 当前 MTU−3 | 由上层协议决定合法分片，或请求合适 MTU；不能无条件截断 |
| Transport | message、可选 cause | 原生拒绝/错误状态、扫描失败/缓冲溢出等真实传输失败 |

`operation`/message 是诊断字符串，不是固定机器枚举。`Transport` 可保留原始 cause，不应丢弃。扫描期限非正与 MTU 超范围使用 `IllegalArgumentException`；调用方的 `CancellationException` 原样透传。使用宽泛异常处理时先透传取消，不把取消包装成“蓝牙失败”或成功。JVM 工厂的不可用是 `UnsupportedOperationException`，不是 `BtException`。

## 完整使用示例

下列代码都使用当前 API。地址和服务/特征 UUID 由调用方基于设备真实公开契约传入，不硬编码某个协议或声称任意特征可读。示例不是授权 UI，也不是桌面蓝牙模拟器。

### Android：独立读取一次并关闭自有管理器

完整函数，可放在 Android 源集中。前提：宿主已经完成 Manifest/运行时授权，系统无线已开启，调用方对该目标连接拥有独占资源权限；从自身协程调用，无需阻塞 UI。它创建和关闭自己的管理器，因此不要拿它替代共享管理器中正在运行的其他组件。

```kotlin
import android.content.Context
import ink.lipoly.app.sunrise.blueConnector.BtException
import ink.lipoly.app.sunrise.blueConnector.BtPermissions
import ink.lipoly.app.sunrise.blueConnector.GattProperty
import ink.lipoly.app.sunrise.blueConnector.createBtManager
import java.util.UUID

suspend fun readGattValueOnce(
    context: Context,
    address: String,
    serviceUuid: String,
    characteristicUuid: String,
): ByteArray {
    val missing = BtPermissions.missing(context, scan = false)
    if (missing.isNotEmpty()) throw BtException.MissingPermission(missing)
    val serviceId = UUID.fromString(serviceUuid).toString()
    val characteristicId = UUID.fromString(characteristicUuid).toString()
    val manager = createBtManager(context)
    try {
        val session = manager.device(address).gatt.connect()
        val characteristic = session.services
            .filter { it.uuid == serviceId }
            .flatMap { it.characteristics }
            .singleOrNull { it.uuid == characteristicId }
            ?: throw BtException.UnsupportedOperation("requested characteristic")
        if (GattProperty.READ !in characteristic.properties) {
            throw BtException.UnsupportedOperation("requested read")
        }
        return session.read(characteristic)
    } finally {
        manager.close()
    }
}
```

返回的是原生真实读值；查找不到或出现重复目标特征时明确失败，不以空值替代。取消连接等待仍会进入 finally 关闭自有管理器，因而不会留下不再被需要的无线尝试。UUID 参数格式无效由 `UUID.fromString` 抛参数异常，不通过蓝牙层“猜测修复”。

### 公共源：扫描由调用方选择，不擅自连接

完整函数。调用方提供仍存活、已授权扫描的管理器并负责最终关闭它。两过滤值都为空会进行全量 LE 扫描；返回的候选由应用选择，不能据名称推断物理身份。本函数不创建连接，取消时 scanLe 自身负责尽力停止扫描。

```kotlin
import ink.lipoly.app.sunrise.blueConnector.BtDevice
import ink.lipoly.app.sunrise.blueConnector.BtManager

suspend fun discoverTransportCandidates(
    manager: BtManager,
    exactName: String? = null,
    knownAddress: String? = null,
): List<BtDevice> = manager.scanLe(
    name = exactName,
    address = knownAddress,
    timeoutMillis = 8_000,
)
```

调用者可改用 `bondedDevices()` 或 `refreshConnectedAudioDevices()` 获取另一种事实，不需要扫描才能通过已知地址连接。此函数没有吞掉权限/传输错误，空结果与异常仍可区分。

### Android：先订阅再启用，读取一条通知并释放自有连接

完整函数，使用外部管理器但拥有目标设备 GATT 的**独占生命周期**。前提：无线/权限已就绪，该设备上没有其他依赖同一会话的组件。它不关闭注入的 manager，只在成功、失败或取消后断开自己拥有的设备连接，因此不得用于共享会话消费者。服务和特征 UUID 从真实外设契约传入；只接收所选句柄的值，不把任意广播当请求回复。

```kotlin
import ink.lipoly.app.sunrise.blueConnector.BtException
import ink.lipoly.app.sunrise.blueConnector.BtManager
import ink.lipoly.app.sunrise.blueConnector.GattEvent
import ink.lipoly.app.sunrise.blueConnector.GattProperty
import java.util.UUID
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

suspend fun awaitOneGattNotification(
    manager: BtManager,
    address: String,
    serviceUuid: String,
    characteristicUuid: String,
    timeoutMillis: Long,
): ByteArray {
    require(timeoutMillis > 0)
    val serviceId = UUID.fromString(serviceUuid).toString()
    val characteristicId = UUID.fromString(characteristicUuid).toString()
    val device = manager.device(address)
    try {
        val session = device.gatt.connect()
        val characteristic = session.services
            .filter { it.uuid == serviceId }
            .flatMap { it.characteristics }
            .singleOrNull { it.uuid == characteristicId }
            ?: throw BtException.UnsupportedOperation("requested characteristic")
        if (GattProperty.NOTIFY !in characteristic.properties &&
            GattProperty.INDICATE !in characteristic.properties
        ) {
            throw BtException.UnsupportedOperation("requested notification")
        }
        // 从订阅开始计时；包括启用 CCCD 和等待首条通知，不含前面的连接。
        return withTimeout(timeoutMillis) {
            coroutineScope {
                val next = async(start = CoroutineStart.UNDISPATCHED) {
                    session.events.filterIsInstance<GattEvent.ValueChanged>()
                        .first { it.characteristic === characteristic }
                        .value.copyOf()
                }
                session.setNotifications(characteristic, enabled = true)
                next.await()
            }
        }
    } finally {
        // 取消连接等待也必须显式停止资源；NonCancellable 只覆盖关闭。
        withContext(NonCancellable) { device.gatt.disconnect() }
    }
}
```

这里的 `withTimeout` 是调用方的整体通知等待取消，不是原生 GATT 期限，也不代表协议不支持。若取消发生在已开始的 CCCD 写期间，原生 worker 本来会继续排空；本例因为明确拥有整个连接，finally 有意断开资源。共享消费者则应只取消自己的收集任务，不断开共享会话，并由所有者协调 CCCD。真实返回值才是收到了通知；函数不打印预设“成功”，也不推断发送命令已生效。

## 常见误区与验证边界

1. **句柄、发现、音频连接、GATT CONNECTED、上层协议 READY 是五种不同事实。** 不用其中一种替代另一种。
2. **同名匹配不证明同一物理设备。** 本层不自动把系统音频地址关联到另一 LE 地址；需要应用策略时看 [Headset](../headset/Docs.md)。
3. **成功写入不是设置读回确认。** 需要协议事务或控制状态时看 [Drop](../drop/Docs.md)，不要在通用层引入协议 UUID/品牌逻辑。
4. **旧特征不能跨重连。** 保存 UUID 查询条件可以，保存旧 characteristic 当新会话句柄不可以。
5. **取消等待不是关闭资源。** 尤其 connect 的共享无线任务，以及已开始的原生操作；需要结束连接必须由所有者显式释放。
6. **请求 247 不保证 MTU 为 247。** 看返回值/mtu，并按实际 MTU−3 检查负载；本层只提供带响应单包写。
7. **扫描超时不是错误、空结果不是存在性证明。** null scanner、profile 代理超时可返回空；真正 onScanFailed/溢出不可伪装为成功。
8. **SharedFlow 不是历史存储。** 先订阅再开启通知，状态恢复用 StateFlow；慢原始通知处理会导致明确溢出关闭，而不是无限积压。
9. **关闭不是系统解绑。** session/manager close 只释放所属通讯资源，不关闭系统蓝牙，也不改变配对关系。
10. **桌面没有生产蓝牙后端。** 不创建 JVM manager 来模拟 Android 成功；测试 fixture 只证明纯协程/协议行为。

本参考从当前源代码契约描述行为，不声明已经验证某款实体外设、厂商系统、无线干扰环境、授权界面或 Android 回调时序。实际 Android 验证必须具备真实适配器、正确授权及目标 GATT 外设；API 24–32/33+ 分支、系统配对结果、音频 profile 可见性、MTU 实际协商和通知流量仍应在对应平台观察。纯 JVM 测试不能替代这些无线路径，本次文档工作也不以虚构后端补足平台边界。
