# Drop 协议控制参考

`ink.lipoly.app.sunrise.drop` 是平台无关的 **GAIA GATT / 9ECA GATT 协议控制层**。它消费调用方提供的 `BtDevice` 及其现有 GATT 会话，探测能力、匹配回复、解释控制数据，并发布功能状态；它不是蓝牙发现器或应用的单耳机连接器。

## 导航

- [职责、依赖与源码索引](#职责依赖与源码索引)
- [控制器 API 与生命周期](#控制器-api-与生命周期)
- [初始化与能力完整性](#初始化与能力完整性)
- [状态、事件与模型](#状态事件与模型)
- [GAIA 控件方法参考](#gaia-控件方法参考)
- [9ECA 控件方法参考](#9eca-控件方法参考)
- [ANC 写入与确认](#anc-写入与确认)
- [配置匹配与独立读写映射](#配置匹配与独立读写映射)
- [帧、特征与事务匹配](#帧特征与事务匹配)
- [错误、期限与取消](#错误期限与取消)
- [完整接入示例](#完整接入示例)
- [常见误用与平台边界](#常见误用与平台边界)

## 职责、依赖与源码索引

三个包在同一 `shared` 模块，依赖方向为 `headset → drop → blueConnector`；Drop 不依赖 Android、Compose 或 headset。

| 所有者 | 负责 | 不负责 |
| --- | --- | --- |
| [blueConnector](../blueConnector/Docs.md) | 稳定设备句柄、平台发现/配对请求、每设备 GATT、每会话原生操作队列与回调 | 识别 GAIA/9ECA、耳机选择、协议解码 |
| 本包 `DropController` / 内部 binding | 当前 GATT epoch 的探测、事务锁、回复槽、控件、功能状态 | scan/connect/disconnect、品牌筛选、自动重连、设备关联、周期电量轮询 |
| [headset](../headset/Docs.md) | 本应用单耳机选择、音频地址→BLE 端点关联、显式 GATT、重试、ANC/电量初读和 30 秒电量轮询、Android 接入 | 替代通用通讯契约或协议编解码 |

旧混合接入说明中的权限/Activity/Compose 生命周期、单耳机候选选择、关联持久化、轮询与桌面界面行为应从上面两份指南阅读；不能把这些策略理解为 `DropController` 的隐式行为。只有 GATT，不提供 RFCOMM，也不增加品牌名单。

| 文件 | 内容与入口 |
| --- | --- |
| [DropApi.kt](DropApi.kt) | 状态/能力/事件/异常、选项、`GaiaControls` / `SourceControls` 公共方法 |
| [DropController.kt](DropController.kt) | 构造、状态观察、`awaitReady()`、控件 getter、关闭与 epoch 守卫 |
| [DropControlBinding.kt](DropControlBinding.kt) | 单会话初始化、GAIA/9ECA 回复匹配、通知解释、错误映射 |
| [DropControlSession.kt](DropControlSession.kt) | 控件所需的单绑定内部接口，不是动态转发到当前连接的 facade |
| [DropGattIds.kt](DropGattIds.kt) | 完整小写协议 UUID；不属于通用通讯包 |
| [GaiaProtocol.kt](GaiaProtocol.kt) | `GaiaCommand` / `GaiaPacket`、`GaiaIds`、`GaiaCodec` |
| [GaiaBluetrumPeqCodec.kt](GaiaBluetrumPeqCodec.kt) | 严格 Bluetrum PEQ 负载、原始量纲转换、MTU 批量上限 |
| [SourceProtocol.kt](SourceProtocol.kt) | 9ECA 全部值模型、`SourceIds`、`SourceCodec` |
| [GaiaControlsImpl.kt](GaiaControlsImpl.kt) | GAIA 参数检查、读回、功能快照更新 |
| [SourceControlsImpl.kt](SourceControlsImpl.kt) | 9ECA 状态检查、音源稳定等待、SET 响应与固件 fallback |
| [AncVerification.kt](AncVerification.kt) | 一次 SET、最多四次 GET 的 ANC 确认算法 |
| [DropProfile.kt](DropProfile.kt) | 身份匹配、内置映射、覆盖合并及独立读写编号 |

通讯包名是 `ink.lipoly.app.sunrise.blueConnector`；`Bt*`、`Gatt*` 与 `createBtManager` 仍使用原类型/工厂名，没有新的 `BlueConnectorManager` 等替代名称。

## 控制器 API 与生命周期

```kotlin
// 签名速查，不是独立运行示例；device/profileDevice 是调用方持有的 BtDevice。
DropController(device, options = DropOptions(), profileDevice = device)
```

| API | 契约 |
| --- | --- |
| `device: BtDevice` | 实际通讯端点。其管理器、GATT 连接由调用方拥有 |
| 构造参数 `options` | `DropOptions.profileOverrides`，仅影响编号配置 |
| 构造参数 `profileDevice` | 匹配配置使用的地址/名称。可与通讯端点不同；每次新绑定读取其当时 `info.value.name` |
| `state: StateFlow<DropState>` | 当前功能快照，有初值；晚订阅读当前值恢复 |
| `events: SharedFlow<DropEvent>` | 无重放、best-effort 的瞬时通知；不是事务匹配通道 |
| `gaia: GaiaControls` / `source: SourceControls` | 返回当前 READY 绑定的控件，不保证该协议存在。无 READY 绑定抛 `NotReady`，已关闭抛 `Disconnected` |
| `suspend awaitReady(): Unit` | 为当前已经 CONNECTED 的会话启动/共享一次初始化并等待；没有现有会话立即 `NotReady`，不等未来连接 |
| `close(): Unit` | 幂等同步退休绑定、失败化 pending/初始化等待、取消自有 scope，并恢复默认 `DropState()`；不关闭 GATT/管理器 |

### 构造与共享初始化

构造仅启动 `device.gatt.state` 收集。构造在连接前、在连接后，以及显式 `connect()` 返回后立即调用 `awaitReady()`，都使用同一会话绑定路径：

1. 读取 **最新** GATT 快照，只接受 `GattPhase.CONNECTED` 的会话，不依赖已错过的事件。
2. 若已有 binding 的 session **引用身份与 id 均相同**，复用它；否则退休旧绑定并创建新绑定。
3. 短生命周期 Mutex 只保护安装/替换，不持锁等待无线 I/O。同步 `close()` 不等待此锁，因此安装之后再次校验关闭标志与 epoch。
4. 状态观察与 `awaitReady()` 共享 binding 的一次启动标志和 `CompletableDeferred<Unit>`；同 epoch 不重复探测。
5. 初始化失败可在 state 中保留 `ERROR/error`，随后 binding 关闭；同一 GATT 会话不会自动重新探测。调用方决定是否显式建立新会话。

单个调用者取消 `awaitReady()` 只取消该次等待，不取消共享初始化或 GATT。断连、换会话或控制器关闭使绑定等待/请求失败；初始化结束前还要核对当前 epoch，不能在旧会话上发布 READY。

### 控件引用与并发

控件对象属于 **某个绑定**，而不是永久“当前设备”代理。普通动作重新取 getter；实时编辑器可在一个连接 epoch 内固定持有该对象，换绑时必须关闭并丢弃旧草稿，不能在旧 worker 内获取新 getter。旧引用失效后抛 `Disconnected`，不能把请求或旧读值发送到新会话。

- 一个 binding 内 GAIA 和 9ECA 共用事务 Mutex，每次请求只有对应 pending 槽；不同设备的控制器各自独立，可以同时等待回复。
- Codec SET+GET、完整 PEQ 读取/批量写入/验证和预设 SET+GET 在一次 `withGaiaTransaction` 内持有同一个 Mutex；不与 GAIA 或 9ECA 请求交错。其他多步骤动作（例如 ANC 读回、音源轮询）仍由多次事务组成，不自动成为大事务。
- 隔离保证针对 **不同设备**。同一设备创建多个控制器并行操作同一协议不在保证范围；响应可能被各自订阅者观察，不应据此建立同设备共享控制方案。
- `close()` 不发送关闭通知命令、不撤销其他使用者的 CCCD，也不等待已开始原生操作的 callback。若调用方拥有连接，需另外断开；借用连接时则不能顺手断开。

## 初始化与能力完整性

### 特征与订阅

协议存在性只看当前会话是否有相应 **command + response** 特征对：GAIA 对存在加入 `GAIA_BLE`，9ECA 对存在加入 `SOURCE_9ECA`。两者都不存在时 `UnsupportedDevice`，控制器 ERROR；普通 GATT 连接本身仍由调用方决定是否断开。

初始化先以 `UNDISPATCHED` 建立会话原始事件订阅，再执行 CCCD/写入，避免快速回复先于订阅。GAIA response 与 9ECA response 通知是 **mandatory**；启用失败使初始化失败。GAIA data 和 9ECA notification 是 **optional**，存在才尝试，拒绝且会话仍有效时可继续。

`requestMtu(247)` 是 best-effort 请求，不承诺最终 MTU=247。拒绝且会话仍有效可继续使用当前 MTU；如果底层原生操作超时使会话失效，不能吞掉失败并宣称 READY。可选操作中的 `CancellationException` 总是透传。

### GAIA 探测

- 先请求 `BASIC/FEATURES`，最多消费八页；成对列表格式为 continuation 字节 + feature/version 字节对，continuation=0 表示完成，后续用 `FEATURES_NEXT`。
- 非成对列表按大端 32 位位图解析；只有负载长度为四的倍数才标记完整。codec 忽略不足四字节的尾部，绑定仍据此判定完整性。
- ANC 路径优先 `AUDIO_CURATION` → `ANC_V2` → `ANC_V1`。能力未给出路径时按相同顺序 GET fallback，首个有回复的路径补入 feature 集合。
- fallback 命中不等于能力页已完整，也不保证返回的 ANC 编号一定可解码；真正调用仍可 `Protocol`。

### 9ECA 探测

优先读取 **可读 info** 特征，解析 12 字节固件信息；特征缺失、读取/解析失败且当前会话仍有效时退到 `GET_FW_VERSION`，检查成功状态后从 offset=1 解码。两条路径都不能提供信息时 `sourceFeatures` 可为空、完整性为 false，而 9ECA 协议仍可 READY。

可读 capability 特征 **不参与协议存在性判定**；它缺失不意味着不支持 9ECA。`readSourceCapability()` 此时抛 `UnsupportedCapability`，`getCapabilityPage(page)` 的命令路径仍可独立尝试。初始化不会自动读取所有音源条目。

### `complete` 的含义

`DropCapabilities.complete = gaiaComplete && sourceComplete`，未出现的协议视为不需要探测。READY 表示识别的协议初始化完成、当前会话仍有效；**不表示所有能力已知、不表示每个 feature 可调用**。空集合且 complete=false 是信息不足，不能显示成“明确不支持”。非必需能力探测失败若会话仍有效可 READY；真正断连/原生超时不得当作可忽略探测失败。

## 状态、事件与模型

### `DropState` / 枚举

| 字段 | 更新来源与未知语义 |
| --- | --- |
| `phase` | `IDLE` 无当前绑定；`PROBING` 初始化；`READY` 有可用协议；`ERROR` 初始化失败。不是应用连接阶段 |
| `protocols` | 当前会话的 GAIA/9ECA 特征对集合；断连清空 |
| `capabilities` | `gaiaFeatures: Set<Int>`、`ancModes: Set<AncMode>`、`sourceFeatures: Set<SourceFeature>`、`complete: Boolean` |
| `battery` | GAIA GET/电量帧按组件 1=左、2=右、3=盒合并。缺组件保留最近值；原始 u8 不裁剪到 100；字段 null 表示未知 |
| `ancMode` | ANC GET 经路径/profile 解码；Unverified 清 null，Mismatch 保留最后实际值 |
| `gain/ledOn/spatialOn/headTracking` | 对应 GET 或 SET 后 GET 的实际读值，初值 null |
| `sourceStatus/volume/presetEq/micGain` | 9ECA 查询、部分 SET 响应和指定通知；初值 null。预设 SET 只修改已有快照的 current |
| `error` | 初始化失败的 `DropException?`；普通控件调用失败直接抛出，不自动写 error |
| `codecStates` | `Map<AudioCodec,Boolean>` 仅保存独立 GET 实际值；缺 key=未知，false=已确认关闭。失败只移除相关 codec |
| `paramEq` | 完整 `GaiaParamEqState?` 设备读回；读不全/无法确认时 null，失配保留完整 actual；不存草稿 |

断连/关闭恢复默认快照，包括未知电量与控制值；错误初始化可保留该 epoch 的 ERROR 快照直到后续断连/替换。通知只可更新当前 binding，旧会话帧不得覆盖新会话。

`AncMode` 是 OFF、NOISE_CANCELLING、TRANSPARENCY、WIND、ADAPTIVE、LIVE；具体可写集合由 ANC 路径及 profile 决定。`GainLevel` 是 LOW/MEDIUM/HIGH，不代表固定 dB。`HeadTrackingMode` 的设备编号按枚举顺序 OFF=0、THIRTY_DEGREES=1、SURROUND=2。`AudioCodec` 为 LC3/LDAC/LHDC 的开关查询目标，不是当前音频编码事实；`EarbudSide` 为序列号耳侧。

### 事件

| 事件 | 字段/发布时机 |
| --- | --- |
| `DropEvent.GaiaNotification(packet)` | type=1 的完整 `GaiaPacket`。GAIA 电量 feature/command 帧还会合并电量，无论是否用于 pending |
| `DropEvent.SourceNotification(commandId,payload)` | type=3 的负载，不含帧头；通知 129/130 更新音源，133 更新音量，134 更新预设 EQ，136 更新麦克风增益 |
| `DropEvent.Error(cause)` | 当前绑定初始化失败的归一化错误，不是所有操作异常的广播 |

`events` 没有 replay，额外容量 32，发布用 `tryEmit`；不保证慢 UI 收到每个事件。内部回复匹配不经过 UI 事件流。9ECA 畸形通知的解析错误不使正在等待的请求失败；通知事件可能已经发布，消费者仍需谨慎解释原始数据。

### 9ECA 数据模型字段

以下 u8/u16/i16/u32 分别表示无符号 8/16、有符号 16、无符号 32 位；多字节均小端。模型构造本身不执行控件写入范围检查。

| 模型 | 字段与意义 |
| --- | --- |
| `SourceSwitchOptions` | `persistDefault=false`→flags bit0；`muteDuringSwitch=false`→bit2；`noBluetoothAutoResume=false`→bit3；`fadeSeconds=5` 秒；`awaitStable=true`。其余 flag 位不由此类设置 |
| `SourceStatus` | `statusCode/currentSource/targetSource/transitionState/activeReason` 均 u8。`stableSuccess` 仅 `(statusCode in {0,9}) && transitionState==0`，不检查目标一致 |
| `SourceEntry` | `sourceId` u8，可作为切换候选；`flags` u8 原始逐项标志，本实现不解释位，也不按位推断支持性 |
| `SourceCapabilityPage` | `page/totalPages` u8，`entries` 最多五项；codec 不验证返回页等于请求页 |
| `SourceCapability` | `version/currentSource/globalFlags` u8，`entries` 最多八项；globalFlags 原样保留 |
| `SourceFirmwareInfo` | `protocolMajor/protocolMinor` u8，`featureFlags` u16；固件 `major/minor/patch/buildType` u8，`buildId` u32→Long。buildType 不映射名称 |
| `SourceVolume` | `current/minimum/maximum/step/muteState` 全 u8。音量单位设备定义，静音码不转换 Boolean |
| `SourcePresetEq` | `current/count/editablePreset` 全 u8，不提供预设名称 |
| `SourcePresetEqChange` | `current/previous/editablePreset` 全 u8，来自 SET 响应 |
| `SourcePeqConfig` | `preset/pointCount` u8，`revision` u16，`preGainCentiDb` i16（百分之一 dB），dirty 字节非零为 true |
| `SourcePeqPreGain` | `preset` u8，`centiDb` i16（百分之一 dB），dirty 非零为 true |
| `SourcePeqPoint` | `index` u8、`frequencyHz` u16（Hz）、`gainCentiDb` i16（百分之一 dB）、`qRaw` u16、`filterId` u8；Q 缩放与滤波器名称不在当前实现定义 |
| `SourcePeqCommit` | `revision` u16、`activePreset` u8、dirty 非零为 true |
| `SourceMicGain` | `currentDeciDb/minDeciDb/maxDeciDb/stepDeciDb` 均 i16，十分之一 dB |

固件 `features` 位映射：bit0 PRESET_EQ、bit1 PEQ、bit2 AUDIO_SOURCE、bit3 VOLUME、bit4 MIC_GAIN；其余位忽略。不要用 `featureFlags` 未定义位发明功能。`ByteArray` 模型字段是原始字节，不自动解码文本；Kotlin 数据类不提供数组内容相等保证。

## GAIA 控件方法参考

每个调用需要当前 READY 绑定及 `GAIA_BLE`。getter 不检查协议，具体传输入口会检查。多数既有 SET 是“匹配 SET 回复 → 独立 GET”；ANC、codec 和 PEQ 不依赖 SET ACK。基本取值至少一个负载字节；既有布尔控件非零即 true，但 codec/EQ STATE 严格只接受 0/1。

### 电量、ANC 与增益

| 方法 | 输入/结果/状态 |
| --- | --- |
| `getBattery()` | 返回 `EarbudBattery(left,right,case)` 并更新 battery。响应必须至少两字节且偶数长，未知组件 ID 忽略 |
| `getAncMode()` | 根据固定 ANC 路径及读 map 解码并更新 ancMode；路径 UNKNOWN→UnsupportedCapability，未知编号→Protocol |
| `setAncMode(mode)` | mode 须存在于可写映射；一次单向 SET 后最多四次 GET，返回确认模式；详见 ANC 章节 |
| `getGain()` | GET DAC 原始编号，逆查 gainWrite，更新 gain；未知编号→Protocol |
| `setGain(level)` | 按 gainWrite 编号 SET，等响应后 GET；返回/保存实际档位 |

### 开关、追踪与音乐

| 方法 | 输入/结果/状态 |
| --- | --- |
| `isLedOn()` / `setLedOn(on)` | GET 更新 ledOn；SET 写 0/1 后 GET 更新并返回 |
| `isSpatialOn()` / `setSpatialOn(on)` | GET 更新 spatialOn；SET 写 0/1 后 GET 更新并返回 |
| `getHeadTracking()` / `setHeadTracking(mode)` | GET 编号须为 0..2，更新 headTracking；SET 枚举 ordinal 后 GET |
| `isCodecEnabled(codec)` / `setCodecEnabled(codec,enabled)` | LC3/LDAC/LHDC GET 严格 0/1 并缓存 `codecStates`。SET 单事务发送后 GET；失配抛 `CodecStateMismatch` 且保留 actual，读回失败移除 key 并抛 `Unverified`。不说明系统实际音频编码 |
| `isDynamicBassOn()` / `setDynamicBassOn(on)` | 查询或写 0/1 后 GET；不缓存 DropState |
| `isLeftRightReversed()` / `setLeftRightReversed(reversed)` | 查询或写 0/1 后 GET；不缓存 DropState |
| `getEqualizerPreset()` / `setEqualizerPreset(index)` | GET 原始 u8；SET index=0..255 后 GET，动作先使 paramEq 失效；不推断预设数量/名称 |

### GAIA Bluetrum 参数 EQ

- `getParamEq(): GaiaParamEqState` 在单事务读取 STATE、PRESETS、PRESET、BAND_COUNT 和全部连续范围，完成后才发布。STATE 必须为 1，预设列表严格 `count + count个ID` 且含 USER=63，段数 1..255。STATE=0、无 USER63、未知 filter/不符合 Bluetrum 布局时不可编辑；不能靠设备名或 feature5 宣称支持。9ECA USER=7 和其 PEQ API 不变，普通 EQ 页面不使用该后端。
- `GaiaPeqBand(index,frequencyHz,gainRaw,qRaw,filter)` 按设备固定索引排列，不按频率重排。`gainDb=gainRaw/60.0`、`q=qRaw/4096.0`；频率 u16、Q u16、gain s16BE。启用段频率20..20000Hz、qRaw1..65535；Bypass 的频率/Q 原始 u16 可为0，未修改字段原样透传。数值输入先拒绝非有限和溢出，再以最近整数 Hz、gain×60/Q×4096 向零截断转换，不能从显示舍入值重建未改 raw。
- `PeqFilter` IDs：BYPASS=0、LOW_PASS=7、HIGH_PASS=8、LOW_SHELF=10、HIGH_SHELF=11、PEAKING=13。配置负载为 `start:u8,end:u8,totalGain:s16BE,N×(frequency:u16BE,q:u16BE,filter:u8,gain:s16BE)`，严格 `4+7N` 字节。[INFERENCE] offset2..3 与写入 totalGain 对应；只保留原始 s16，不提供总增益控制，也不声明该 header 是已确认的 dB 读值。
- 每个范围最多 `min(7,floor((MTU−3−8)/7))` 段，GET 和 SET 都使用此上限以容纳完整同尺寸回复。MTU23 每包1段、MTU247 最多7段；PDU完整写入，无通用分片/截断，也不拼猜通知聚合。
- `setParamEq(bands): GaiaParamEqState` 接受全部频段快照，写前整体检查所有 raw/连续索引并重新确认 BAND_COUNT。按已确认 header 批量发送 command6，每批立即 GET 同范围精确比较 raw；首个失配停止后续写，读取完整 actual 并抛 `ParamEqMismatch(observed)`。读不全则清 paramEq 并抛 `Unverified`，不能说全量已应用。
- 配置逐批确认后才需要时发送 command3 USER63，再 GET 确认激活并完整重读。页面加载零写入；编辑才切用户 EQ。Codec 选项不添加互斥规则、不自动重置 EQ，应用在 codec 修改后重新读取 EQ 以设备响应判断可用性；LHDC 限制不适用于所有型号。
- 没有自动重试、回滚或 Flash 保存；command7/8 不由结构化 PEQ 调用。写后失败可能已部分应用，取消/断连仍原样传播并尽力清本绑定快照。读回确认不等于断电持久化、设备设置不等于系统协商结果。

### 手势、基础信息与原始命令

| 方法 | 输入/结果/确认边界 |
| --- | --- |
| `getGestureConfiguration(gesture,context)` | 两参数各 0..255；返回 `GaiaPacket`，不解析配置内容 |
| `resetGestureConfiguration()` | 返回匹配 RESET 响应，不额外 GET/持久化确认 |
| `getBasicInfo(command)` | 只允许 Basic 的 VERSION=0、FEATURES=1、SERIAL=3、VARIANT=4、APP_VERSION=5、COLOR=18、LANGUAGE=19、LEFT_SN=20、RIGHT_SN=21、TWS_STATUS=22；返回原始包 |
| `getAudioCuration(command)` | 允许 0,2,3,5,7,8,10,12,13,15,17,18,20,22,23,25,26,28,30,31,33,34,35,37,38,39,41；返回原始包，无自动状态解释 |
| `setAudioCuration(command,payload)` | 允许 1,4,6,9,11,14,16,19,21,24,27,29,32,36,40,42 且 payload 非空；先复制负载，仅等匹配响应 |
| `powerOff()` | 只完成传输写，不等协议 ACK，不主动 disconnect，不证明物理关机 |
| `requestRaw(command: GaiaCommand)` | 等同 vendor/feature/command 的 RESPONSE；不统一检查 GAIA 成功/拒绝负载，不保证物理应用 |
| `sendRaw(command: GaiaCommand)` | 无 pending 槽，只等传输写，不等协议响应，不报告已应用 |

`GaiaIds` 顶层常量是 feature ID，各嵌套对象是所属 feature 的命令 ID。大量编号仅供原始接口，存在常量不意味着已有结构化控件或设备支持；请查 [GaiaProtocol.kt](GaiaProtocol.kt) 的各项 KDoc。原始 AUDIO_CURATION 参数、未定义状态/标志、时长或增益单位不能从命令名称推断。

## 9ECA 控件方法参考

需要 READY 与 `SOURCE_9ECA`。普通请求先读响应第零字节状态：非零 `Rejected`，再解释结构。SET_AUDIO_SOURCE 特许状态 5/9；其余方法不会因为状态 9 被 `stableSuccess` 接受而放宽普通请求检查。

### 音源、能力与固件

| 方法 | 范围/返回/状态 |
| --- | --- |
| `getAudioSource()` | 返回并更新 `SourceStatus`；普通 GET 非零状态拒绝 |
| `setAudioSource(sourceId,options)` | sourceId=0..255，fadeSeconds=0..60。ID 必须来自真实 entries；保存首次 SET 状态，默认稳定等待见下文 |
| `getCapabilityPage(page)` | page=0..15，返回页号/总页数/最多五项 entries；不缓存能力条目 |
| `readSourceCapability()` | 直接读取可读 capability 特征，解析最多八项；缺失/不可读→UnsupportedCapability。不与命令分页格式混用 |
| `getFirmwareInfo()` | 直接 info 优先，失败 fallback GET_FW_VERSION；取消透传，更新 capabilities.sourceFeatures；不自动把 complete 改为 true |

`setAudioSource` 默认 `awaitStable=true`：首次响应若已 stableSuccess 且 currentSource=目标则直接返回，否则每 200ms GET，期限 `(max(fadeSeconds,1)+3) 秒`。循环允许 current.statusCode 为 0/5/9；最终非 0/9 拒绝。注意 GET 本身仍执行普通首状态码检查，GET 返回 5/9 时会先抛 Rejected；不要把循环条件当成所有响应都允许 5/9 的全局规则。`awaitStable=false` 直接返回首响应；状态 9 在某些返回路径也可能尚未稳定或未到目标，**始终检查 stableSuccess 和 currentSource**，不能仅依据“方法返回了”。

### 音量、预设 EQ 与麦克风

| 方法 | 范围/返回/状态 |
| --- | --- |
| `getVolume()` | 返回设备 current/minimum/maximum/step/muteState，并更新 volume |
| `setVolume(mode,value,flags=0)` | 三参数各 0..255；只解析 SET 响应并更新 volume，不另 GET；mode/flags 的设备语义本实现不命名 |
| `getPresetEq()` | 返回 current/count/editablePreset 并更新 presetEq |
| `setPresetEq(index)` | index=0..255，返回 current/previous/editablePreset。只修改已有 state.presetEq.current；原值 null 则仍 null，不伪造 count |
| `getMicGain()` | 返回四个十分之一 dB 字段并更新 micGain |
| `setMicGain(deciDb)` | -1280..1280，即 -128..128 dB 的编码范围；仅保存 SET 响应，不另 GET，设备报告边界仍须尊重 |

### 参数 EQ（PEQ）

以下操作仅返回值模型，不加入 `DropState` 快照。不能把模型的编码范围当成设备实际支持范围。

| 方法 | 范围/返回 |
| --- | --- |
| `getPeqConfig()` | 返回 preset/pointCount/revision/preGainCentiDb/dirty |
| `setPeqPreGain(centiDb)` | 固定预设 `SourceIds.USER_PEQ_PRESET=7`，-12800..12799 百分之一 dB；返回 preset/centiDb/dirty |
| `getPeqPoint(index)` | index=0..31；返回频率、增益、qRaw、filterId |
| `setPeqPoint(point)` | index=0..31；frequencyHz=20..20000 Hz；gainCentiDb=-32768..32767；qRaw=1..65535；filterId=0..7；返回 SET 点响应，不另 GET |
| `commitPeq(action,revision)` | action=0..2，revision=0..65535；返回 revision/activePreset/dirty。当前实现不定义 action 0/1/2 的固件语义 |

### 设备信息与连通性

| 方法 | 范围/返回 |
| --- | --- |
| `getEarbudColor()` / `getEarbudLanguage()` | 返回响应第二字节原始 ID，不映射名称 |
| `getEarbudSerial(side)` | LEFT/RIGHT 分别查询偏移 0 和 10，每块需总长=20、偏移相符、块长=10；拼接 20 字节，不解码字符串 |
| `ping()` | 等待成功状态回复，返回 Unit；不更新功能状态、不证明其他功能可用 |

## ANC 写入与确认

ANC 是最不能用“SET 返回”替代确认的操作：部分设备应用 SET 却不给匹配 GAIA ACK。实现明确使用单向写：

1. 用当前路径及 **write map** 将目标模式编码，发送一次 SET；写入失败直接抛出，不包装成读回失败。
2. 等待 300ms 后 GET，用 **read map** 解出实际逻辑模式并写入 state.ancMode。
3. 实际值不等于目标时，间隔 500ms 再读，合计最多四次；从不重发 SET。
4. 任一次实际值等于目标就返回该确认值。四次均不匹配抛 `AncModeMismatch(requested,observed)`，保留最后实际值。
5. 读回异常（除取消、Disconnected、NotReady）包装成 `Unverified("ANC mode",cause)`；控件清 `ancMode=null`，避免 UI 继续显示未经确认的模式。

调用方取消、断连和 NotReady 不转为“成功”，也不伪造读回。不承诺从 SET 到确认的总耗时为固定 300/500ms：无线写、每次 GET 的原生/协议等待也占用时间。

## 配置匹配与独立读写映射

`DropOptions.profileOverrides: List<DropProfile>` 不绑定或选择蓝牙设备。解析仅使用 `profileDevice.address` 与 `profileDevice.info.value.name`：

1. 找第一个匹配内置 profile；名称子串忽略大小写，空白子串不匹配。
2. 在 overrides 中优先取第一个匹配的 `DropProfileMatch.Address`（地址全等、忽略大小写），即使某名称覆盖在列表更前也让地址优先。
3. 若无地址覆盖，取第一个任何匹配覆盖；同类先到先用，不聚合多个覆盖。
4. 覆盖中的 null 字段继承内置 profile 的同字段；**不按 map 条目合并**。非 null 空 map 不是继承。没有内置/覆盖时使用路径默认映射。

| 字段 | 作用/缺省行为 |
| --- | --- |
| `match` | `Address(value)` / `NameContains(value)`，配置身份，不是发现过滤 |
| `audioCurationWrite` | AncMode→设备写编号，默认 OFF/NC/TRANSPARENCY/WIND=1/2/3/4 |
| `audioCurationRead` | 设备读编号→AncMode；命中优先，否则逆查 write/default map |
| `ancV2Write` | 默认 OFF/NC/TRANSPARENCY/WIND/ADAPTIVE/LIVE=0/1/2/3/4/5 |
| `ancV2Read` | 命中优先，否则逆查 write/default map |
| `gainWrite` | LOW/MEDIUM/HIGH→设备编号，同时供读回逆查，默认 0/1/2；自定义必须覆盖三档，缺键不会自动回退 |

V1 固定 OFF/NC=0/1，不用 AC/V2 map。`capabilities.ancModes` 从所选路径 **可写 map 的键** 取得，不从 read map 推断支持。未知读编号返回 Protocol；重复映射编号的逆查取遍历首项，不建议构造歧义。

内置 GOLDEN AGES 2 / SPACE TRAVEL 2 的 AC write 为 OFF=1、NC=2、TRANSPARENCY=4、WIND=3，read 为 0/1/2/3→OFF/NC/TRANSPARENCY/WIND；gain 为 LOW/MEDIUM/HIGH=2/1/0。PUDDING 的 V2 write 为 OFF=0、NC=4、TRANSPARENCY=2、WIND=3、ADAPTIVE=1，read 对应这些编号，gain 默认。NEKOCAKE/PILL/MOCA 仅显式使用默认 gain。这些名称表 **只影响映射，不是品牌过滤、不证明固件版本或物理设备**。

字段编号最终以字节提交，本层不替自定义 profile 检查完整性/编号范围。只有经固件实测的映射才应配置；不要从发送编号推断 GET 编号。音频地址不同于 BLE 时，传 `DropController(endpoint, options, profileDevice = audioDevice)`；profileDevice 不触发扫描/切换/连接，关联策略属于 [headset](../headset/Docs.md)。

## 帧、特征与事务匹配

### GATT UUID

| 用途 | 完整 UUID |
| --- | --- |
| GAIA service | `00001100-d102-11e1-9b23-00025b00a5a5` |
| GAIA command | `00001101-d102-11e1-9b23-00025b00a5a5` |
| GAIA response | `00001102-d102-11e1-9b23-00025b00a5a5` |
| GAIA data（可选） | `00001103-d102-11e1-9b23-00025b00a5a5` |
| 9ECA service | `9eca0000-7f3a-4f32-9a38-a91b2c6e0100` |
| 9ECA command | `9eca0001-7f3a-4f32-9a38-a91b2c6e0100` |
| 9ECA response | `9eca0002-7f3a-4f32-9a38-a91b2c6e0100` |
| 9ECA notification（可选） | `9eca0003-7f3a-4f32-9a38-a91b2c6e0100` |
| 9ECA capability（可选且需 READ） | `9eca0004-7f3a-4f32-9a38-a91b2c6e0100` |
| 9ECA info（可选且需 READ） | `9eca0005-7f3a-4f32-9a38-a91b2c6e0100` |

特征查找使用规范小写 UUID，原始通知分派核对当前会话特征 **引用身份**。不能拿旧会话句柄读写新会话，或仅用字符串 UUID 忽略 epoch。

### GAIA codec 与匹配

`GaiaCommand(feature,command,payload=empty,vendor=0x001D)` 编码时检查 vendor=0..65535、feature/command=0..127。PDU 为大端 vendor u16 + 大端 commandWord u16 + payload；word 的位 9..15 为 feature，位 7..8 为 type，位 0..6 为 command；编码请求 type=0。

`GaiaCodec.decode(bytes)` 不足四字节返回 null，其余拆字段并复制 payload，不验证 type 是否已知（可能返回 3）、成功状态或设备能力。`GaiaCodec.features(payload)` 接受 continuation+ID/version 对或大端位图；只返回 feature 集合，不独立判断分页完整性。

绑定以 `type=RESPONSE(2)` 且 **vendor + feature + command 全相等** 完成 GAIA pending。结构化 feature5/command5 GET 还匹配前两字节 start/end；错范围迟到帧被忽略，短于两字节的同三元组回复交严格解析失败，不变成不透明超时。command6 ACK 不会完成 command5 GET。GAIA 没有事务序号；同三元组且同范围的迟到响应仍无法凭线协议区分前后请求，不宣称任意同会话迟到回复的完全隔离。

### 9ECA codec 与匹配

帧头六字节为 `[0xA5, version=1, type, commandId, sequence, payloadLength]`。`SourceCodec.encode` 编码 type=COMMAND(1)，commandId/sequence=0..255，payload 最多 14 字节。`decode` 只接受 RESPONSE=2/NOTIFICATION=3；头/长度非法返回 null，声明负载之后额外尾字节忽略，不做流拼帧。`SourceFrame(type,commandId,sequence,payload)` 的负载是独立副本。

请求序号每个绑定从 0 开始按 u8 循环。pending 只匹配 RESPONSE 的 **commandId + sequence**。新会话有独立 pending/序号，旧会话帧由 epoch 守卫丢弃；字节序号会回绕，不把它理解为永不重复的全局 ID。

`SourceIds`：GET/SET_AUDIO_SOURCE=1/2，GET_CAPABILITY=3，GET_FW_VERSION=4，GET/SET_VOLUME=5/6，GET/SET_PRESET_EQ=7/8，GET_PEQ_CONFIG=9，SET_PEQ_PREGAIN=10，GET/SET_PEQ_POINT=11/12，COMMIT_PEQ=13，GET/SET_MIC_GAIN=14/15，GET_COLOR/LANGUAGE/LEFT_SN/RIGHT_SN=18/19/20/21，PING=127。USER_PEQ_PRESET=7 是预设号，不是命令。

### 9ECA 负载解析器

结构解析不等于成功状态检查；`SourceControls` 先执行 status 检查。以下偏移从负载零开始，直接 capability/info 不带状态字节：

| `SourceCodec` 方法 | 最小结构 / 检查 |
| --- | --- |
| `status` | 至少 1；首字节 u8，否则 Protocol |
| `sourceStatus` | 至少 5；status/current/target/transition/reason |
| `capabilityPage` | 至少 4；status/page/total/count + count×(ID/flags)，count≤5 |
| `sourceCapability` | 至少 4；version/count/current/globalFlags + count×(ID/flags)，count≤8 |
| `firmware(bytes,offset=0)` | 从 offset 至少 12 字节；协议版本2、flags2、固件版本3、buildType1、buildId4；调用方负责非负 offset |
| `volume` | 至少 6；跳过 status，后五字节为模型字段 |
| `presetEq` / `presetChange` | 至少 4；跳过 status，后三字节为模型字段 |
| `peqConfig` | 至少 8；status、preset、pointCount、revision u16@3、preGain i16@5、dirty@7 |
| `peqPreGain` | 至少 5；status、preset、gain i16@2、dirty@4 |
| `peqPoint` | 至少 9；status、index、frequency u16@2、gain i16@4、Q u16@6、filter@8 |
| `peqCommit` | 至少 5；status、revision u16@1、activePreset@3、dirty@4 |
| `micGain` | 至少 9；status 后四个 i16 |
| `earbudInfo` | 至少 2；返回第二字节 |
| `snChunk(payload,expectedOffset)` | 至少 14；字节1=20、字节2=expectedOffset、字节3=10，复制字节4..13 |
| `u8/u16/i16/u32` | 直接偏移读取，无额外边界检查；调用方负责字节足够；u32 返回 Long |
| `le16(value)` | 接受 -32768..65535，输出低16位小端；范围外 IllegalArgumentException |

结构短缺/项数或序列号结构非法抛 `Protocol`；解析器只要求最小长度，通常忽略额外负载。不要把 GET 响应、直接特征值与帧头混在一起解码。

## 错误、期限与取消

### 公开异常

| 异常 | 含义 / 重要载荷 |
| --- | --- |
| `UnsupportedDevice` | 没有 GAIA/9ECA 特征对，不关闭普通 GATT |
| `UnsupportedCapability(capability)` | 缺协议、ANC 路径/模式、可读特征或原生操作不支持 |
| `NotReady` | 没有当前可用 READY 控件/已连接会话 |
| `Timeout(operation)` | 协议回复、音源稳定等待或映射后的原生操作超时；须按来源区分会话是否失效 |
| `Disconnected` | controller/binding 关闭、断连、换会话或旧控件使用 |
| `Unverified(operation,cause)` | ANC/codec/PEQ 写后 GET 无法确认；对应快照未知，PEQ可能部分应用，保留 cause；不包装取消/断连 |
| `AncModeMismatch(requested,observed)` | ANC 读回持续失配；保留实际读回状态 |
| `CodecStateMismatch(codec,requested,observed)` | 编码选项失配，codecStates 保留 observed（false 不等于未知） |
| `ParamEqMismatch(observed)` | PEQ 配置/USER63激活失配；observed 是完整 actual，paramEq 保留它，可能已部分应用 |
| `Protocol(message)` | 包短缺/结构错误/未知枚举/过 MTU 等 |
| `Rejected(status,operation)` | 9ECA 原始状态拒绝，status 保留 u8 数值 |
| `Transport(message,cause)` | 其他底层或初始化异常，保留原 cause |

范围错误由 `require` 抛 `IllegalArgumentException`，不是 `DropException`；原始数组 helper 越界可抛数组异常。不要统一吞异常或把所有错误都解释成“不支持”。

### 通讯错误映射

| `BtException` | `DropException` |
| --- | --- |
| Timeout(operation) | Timeout(operation) |
| Disconnected | Disconnected |
| UnsupportedOperation(operation) | UnsupportedCapability(operation) |
| InvalidGattHandle | UnsupportedCapability("GATT characteristic") |
| PacketTooLarge | Protocol（原消息/过 MTU 说明） |
| 其他，例如 MissingPermission/BluetoothUnavailable/Transport | Transport，保留原异常 cause |

不是每个映射后的异常都保留 cause：前五项是构造对应控制异常；其余使用 Transport 保留。已有 DropException 不改类型；初始化的其他异常也归一化为 Transport。权限申请仍是宿主责任，控制器不会弹授权框。

### 期限与取消所有权

- 协议等待为 **原生写完成后六秒**，不包含事务锁等待与原生写时间。单纯协议超时清 pending，不主动关闭 GATT。
- 当前 Android 通讯实现原生 read/write/descriptor 期限 10 秒、MTU 5 秒；原生超时会失败化队列与会话，拒绝晚 callback。连接/服务发现属于通讯层，详见 [blueConnector](../blueConnector/Docs.md)。
- 请求安装 pending 后再核对 epoch；解绑同时失败化 pending。挂起传输入口和完整 GAIA transaction 都用绑定 lifetime 监听取消调用方的 **子 Job**，解绑转换成 Disconnected。内部 transaction 只能由锁所属协程在 block 内使用，不能逃逸或再次获取同一 Mutex。
- 调用方本来的 `CancellationException` 原样传播；不要 catch Exception 后返回假成功。取消共享初始化等待不取消资源初始化。
- 会话原生队列拥有实际操作：调用方取消时，尚未开始项可跳过；已开始项继续排空 callback/自身期限后才轮到下一项。关闭控制器不顺带断开连接，不保证原生写还没发生，也不回滚设备已应用命令。

## 完整接入示例

以下均为完整导入与函数，使用调用方实际句柄/地址；不构造假生产后端，不申请权限、不替用户选择物理耳机。错误会抛给宿主，宿主应优先透传 `CancellationException` 再展示控制错误。

### 借用已经连接的会话

前提：宿主持有 manager/device，GATT 已 CONNECTED；只创建本函数自己的控制器。构造在连接之后也能从 state 快照绑定。函数关闭控制器 **不**断开借用的会话。

```kotlin
import ink.lipoly.app.sunrise.blueConnector.BtDevice
import ink.lipoly.app.sunrise.drop.DropController
import ink.lipoly.app.sunrise.drop.DropPhase
import ink.lipoly.app.sunrise.drop.DropProtocol
import ink.lipoly.app.sunrise.drop.EarbudBattery

suspend fun readBorrowedBattery(device: BtDevice): EarbudBattery? {
    val controller = DropController(device)
    try {
        controller.awaitReady()
        val snapshot = controller.state.value
        if (snapshot.phase != DropPhase.READY ||
            DropProtocol.GAIA_BLE !in snapshot.protocols) return null
        return controller.gaia.getBattery()
    } finally {
        controller.close()
    }
}
```

检查快照不是锁住无线连接；检查后仍可能断连并抛异常，这是正常竞态，不应转成成功。

### 拥有两个不同设备的连接

前提：两个实际地址不同，宿主已经完成平台授权/打开蓝牙；本函数对这两个设备拥有独占连接生命周期，进入前它们未连接，不能用于会把别人的会话断开的借用场景。注入的 `bt` 由外部释放。

```kotlin
import ink.lipoly.app.sunrise.blueConnector.BtManager
import ink.lipoly.app.sunrise.drop.DropController
import ink.lipoly.app.sunrise.drop.DropPhase
import ink.lipoly.app.sunrise.drop.DropProtocol
import ink.lipoly.app.sunrise.drop.EarbudBattery
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

suspend fun readOwnedPair(
    bt: BtManager,
    addressA: String,
    addressB: String,
): Pair<EarbudBattery, EarbudBattery> {
    val a = bt.device(addressA)
    val b = bt.device(addressB)
    require(a !== b) { "需要两个不同设备" }
    val ca = DropController(a)
    val cb = DropController(b)
    try {
        return coroutineScope {
            val batteryA = async {
                a.gatt.connect()
                ca.awaitReady()
                check(ca.state.value.phase == DropPhase.READY)
                check(DropProtocol.GAIA_BLE in ca.state.value.protocols)
                ca.gaia.getBattery()
            }
            val batteryB = async {
                b.gatt.connect()
                cb.awaitReady()
                check(cb.state.value.phase == DropPhase.READY)
                check(DropProtocol.GAIA_BLE in cb.state.value.protocols)
                cb.gaia.getBattery()
            }
            batteryA.await() to batteryB.await()
        }
    } finally {
        ca.close()
        cb.close()
        withContext(NonCancellable) {
            try { a.gatt.disconnect() } finally { b.gatt.disconnect() }
        }
    }
}
```

一个异步读取失败会取消另一个调用方，finally 仍释放本函数拥有的两连接；控制器关闭与 GATT 断开明确分开。返回的电量来自实际响应，不预设数字。

### 由设备条目和宿主选择切换音源

前提：controller 已 awaitReady，宿主拥有其生命周期；`chooseSourceId` 根据展示给用户的实际 entries 选择，也可返回 null 取消。不硬编码任何音源 ID，零条目不发送 SET。只在缺可读 capability 时 fallback 命令页，其他错误不被伪装成空列表。

```kotlin
import ink.lipoly.app.sunrise.drop.DropController
import ink.lipoly.app.sunrise.drop.DropException
import ink.lipoly.app.sunrise.drop.DropPhase
import ink.lipoly.app.sunrise.drop.DropProtocol
import ink.lipoly.app.sunrise.drop.SourceEntry
import ink.lipoly.app.sunrise.drop.SourceStatus
import ink.lipoly.app.sunrise.drop.SourceSwitchOptions

suspend fun chooseReportedSource(
    controller: DropController,
    chooseSourceId: (List<SourceEntry>) -> Int?,
): SourceStatus? {
    val snapshot = controller.state.value
    if (snapshot.phase != DropPhase.READY ||
        DropProtocol.SOURCE_9ECA !in snapshot.protocols) return null
    val entries = try {
        controller.source.readSourceCapability().entries
    } catch (e: DropException.UnsupportedCapability) {
        controller.source.getCapabilityPage(0).entries
    }
    if (entries.isEmpty()) return null
    val selectedId = chooseSourceId(entries) ?: return null
    val selected = entries.firstOrNull { it.sourceId == selectedId } ?: return null
    val result = controller.source.setAudioSource(
        selected.sourceId,
        SourceSwitchOptions(awaitStable = true),
    )
    check(result.stableSuccess && result.currentSource == selected.sourceId) {
        "音源返回状态尚未确认目标稳定"
    }
    return result
}
```

示例只呈现直接特征或第零命令页；需要全量列表时宿主按设备 totalPages 在 API 0..15 范围内请求后续页。条目 flags/globalFlags 原样交给 UI，不用猜测位含义。示例不会因为第零页无条目而凭空造 ID。

### 使用音频身份与实测映射，确认 ANC

`endpoint` 为已连接 BLE 句柄，`audioDevice` 为宿主已选定的配置身份；`verifiedProfile` 必须来自实际固件验证，示例不虚构编号。关闭这里只释放新 controller，两个传入设备与连接仍归宿主。

```kotlin
import ink.lipoly.app.sunrise.blueConnector.BtDevice
import ink.lipoly.app.sunrise.drop.AncMode
import ink.lipoly.app.sunrise.drop.DropController
import ink.lipoly.app.sunrise.drop.DropOptions
import ink.lipoly.app.sunrise.drop.DropPhase
import ink.lipoly.app.sunrise.drop.DropProfile
import ink.lipoly.app.sunrise.drop.DropProtocol

suspend fun applyMeasuredAncProfile(
    endpoint: BtDevice,
    audioDevice: BtDevice,
    verifiedProfile: DropProfile,
    requested: AncMode,
): AncMode? {
    val controller = DropController(
        device = endpoint,
        options = DropOptions(profileOverrides = listOf(verifiedProfile)),
        profileDevice = audioDevice,
    )
    try {
        controller.awaitReady()
        val snapshot = controller.state.value
        if (snapshot.phase != DropPhase.READY ||
            DropProtocol.GAIA_BLE !in snapshot.protocols ||
            requested !in snapshot.capabilities.ancModes) return null
        return controller.gaia.setAncMode(requested)
    } finally {
        controller.close()
    }
}
```

verifiedProfile.match 应匹配 audioDevice 的地址/名称，不是强制覆盖任意端点；只有实际读回匹配时才返回模式。宿主不能把 Unverified/Mismatch 的异常分支提示成“设置成功”。

### 收集状态与事件、保持取消语义

本函数借用一个 controller，在调用方协程生命周期内收集；调用方取消后两个收集自动结束，不替宿主关闭 controller/GATT。事件回调应轻量，因为 slow UI 不能依赖事件无损。

```kotlin
import ink.lipoly.app.sunrise.drop.DropController
import ink.lipoly.app.sunrise.drop.DropEvent
import ink.lipoly.app.sunrise.drop.DropState
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

suspend fun observeDrop(
    controller: DropController,
    onState: (DropState) -> Unit,
    onEvent: (DropEvent) -> Unit,
): Unit = coroutineScope {
    launch { controller.state.collect { onState(it) } }
    controller.events.collect { onEvent(it) }
}
```

## 常见误用与平台边界

- `BtEvent.OnDiscovered` 不等于 GATT 已连接，更不等于协议/品牌已确认；`awaitReady` 也不会隐式 connect。
- `GattPhase.CONNECTED` 是普通服务发现完成，可能完全没有 GAIA/9ECA。controller 的 UnsupportedDevice 不应被用来否定通讯管理器连接普通设备的能力。
- READY 不等于 complete，也不等于每项功能可用；检查协议、能力及最终错误，不把未知值显示成确定关闭/零电量。
- controller 不提供应用 `HeadsetPhase`、设备选择、自动重连、音频/BLE 关联或 30 秒电量轮询。应用入口和 Android 权限流程见 [headset 指南](../headset/Docs.md)，原生权限/连接/队列见 [blueConnector 指南](../blueConnector/Docs.md)。
- 不缓存跨会话控件，不共用旧特征句柄，不依赖 UI 通知完成请求；晚订阅读 StateFlow，不等历史事件。
- 常规 GAIA SET 多数有 GET，9ECA SET 多数只有响应；sendRaw/powerOff 只有传输完成。不要将所有 setter 的确认等级统一包装成“硬件已应用”。
- 只支持 GATT，没有 RFCOMM，没有发现前协议/品牌过滤。内置 profile 名称仅是编号映射规则。
- Drop 的纯 Kotlin 协议/控制逻辑可被注入通讯驱动；这不构成 Android 无线验证。JVM `createBtManager` 真实不可用并抛 UnsupportedOperationException，不创建假桌面蓝牙。平台工厂与硬件条件见 [blueConnector](../blueConnector/Docs.md)。
- 实体 Android 蓝牙、耳机固件行为、具体映射、Q/滤波器/原始 ID/标志等未定义语义必须结合真机与固件资料验证。此参考以当前源码契约为准，不声称已完成实体硬件验证，不包含随时间失效的测试计数或运行日记。
