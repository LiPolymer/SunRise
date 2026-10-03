# SunRise

> [!caution]
> <img src="https://lipoly.ink/assets/badges/llmWarn.svg" alt="This project contains unaudited AI generated code" width="200">
> 
> under construction

Kotlin Toolchain 0.12.2 项目，包含 Android 应用、JVM 桌面应用和 Android/JVM 共享库。`shared/src/blueConnector` 是协议无关的蓝牙通讯契约，`shared/src/drop` 是平台无关的 GAIA / 9ECA 控制器，`shared/src/headset` 是本应用的单耳机连接与选择策略；`composeLegacy` 是两平台共用的概览、设置和诊断界面。平台蓝牙实现位于 `shared/src@android/blueConnector`，JVM actual 明确不可用。Kotlin 包名仍为 `ink.lipoly.app.sunrise`。

## 运行

Windows 在项目根目录执行：

```bat
kotlin.bat build
kotlin.bat test -m shared
kotlin.bat run -m jvm-app
kotlin.bat run -m android-app
```

Android 构建需要 Android SDK 37；运行需要已连接的设备或模拟器。Android 首次启动请求蓝牙权限；先在系统蓝牙中连接音频耳机，应用会尝试自动连接。多个候选设备时可在选择框中选择耳机；关闭选择框后，概览的“选择耳机”可再次打开设备列表。概览显示连接、电量与降噪，连接地址和协议收在可展开的“连接详情”中，未知值为“—”；ANC 等控制只在能力及读回确认后可用。底部“设置”集中管理主题、语言与界面选项；高级诊断入口在权限与诊断分组中，GAIA、9ECA 和通知测试按需展开。设置存入 `sunrise_ui` 偏好设置，重启后保留。未连接设备时仍可查看界面，设备控制不可用。

JVM 桌面运行同一套界面，但不提供蓝牙客户端：概览和权限区域明确提示当前平台不可用，刷新及连接操作不可用，诊断不提供可执行蓝牙动作。桌面设置只保存在本次进程内，重启恢复默认。

Android Activity 只创建一个 `BtManager`，再通过 `createHeadsetClient(context, bt)` 组合应用 facade；宿主销毁时先 `client.close()` 再 `bt.close()`。Android 12+ 需要 `BLUETOOTH_CONNECT`，扫描还需要 `BLUETOOTH_SCAN`，旧版 Android 的 LE 扫描需要定位权限。只保留 GATT，不支持 RFCOMM，也不增加品牌过滤。原音频→BLE 地址关联和设置偏好数据保留。

编译说明：共享模块用 `-Xexpect-actual-classes` 确认 Kotlin `BtHost` expect/actual 类仍处于 Beta；Android GATT 为兼容 API 24–32 保留旧版回调及写入分支，弃用抑制局限于旧 API 使用处。Material3 明确固定为与 Compose 1.12.1 对应的 `1.12.0-alpha03`，避免自动推断旧版本导致桌面诊断输入框运行时崩溃。

## 通讯与控制

通讯包为 `ink.lipoly.app.sunrise.blueConnector`；仅迁移包与目录，`BtManager`、`BtDevice`、`GattSession`、`BtHost`、`BtPermissions` 和 `createBtManager` 等名称不变。调用方应更新 import，旧包不保留别名或转发。

通用 `BtManager` 以规范地址保存稳定 `BtDevice`，每台设备独立管理 GATT。`OnDiscovered(device, sender)` 只表示系统枚举或扫描观测到了设备，不表示 GATT 已连、品牌匹配或协议可用。

调用方显式 `device.gatt.connect()`，再用 `DropController(device).awaitReady()` 初始化控制。控制器关闭不关闭 GATT；不同设备可同时使用各自的控制器，旧会话帧和旧控件引用不能影响新会话。不保证同一设备上重复控制器并行控制同一协议。

应用继续使用单耳机界面：连接阶段、音频设备身份与错误在 `HeadsetState`，协议、能力、电量与 ANC 在 `state.controls`。0 台音频候选等待，1 台尝试，多个要求选择；缓存端点、音频地址、同名已配对 LE/DUAL、LE 扫描依次尝试。同名不是物理设备关联的证明。ANC 一次 SET 后依据读回确认，失配保留实际模式，无法验证时清除未知值；电量轮询由应用 facade 拥有，而不是控制器。

## Codec 与 ParamEQ

概览的音频编码分组提供 LDAC、LC3、LHDC 设备端开关；每次修改后查询设备确认，读回失配显示实际结果，查询失败显示未知。这些开关不代表当前音频流实际使用的编码。

底部“均衡器”提供 GAIA Bluetrum 参数均衡器，段数来自设备读取，不硬编码 Ultra 的五段。支持 Bell、低/高架、低/高通及 Bypass；频率使用对数刻度，增益和 Q 按协议原始精度保留。手机以大滑杆、微调按钮和单参数数值面板编辑；桌面还支持节点拖动、滚轮调整 Q、带宽把手、Shift 锁轴、方向键微调及双击重置。方向键默认频率约 1%、增益 0.1 dB；Shift 使用粗调，Ctrl 使用精细调。曲线仅为固定 48 kHz 的 RBJ 参数响应估算，不是耳机声学测量。

连续编辑最多每 150 ms 发送最新完整配置，松手刷新最后一笔；写入与读回串行执行，只有读回确认才显示“已应用”。首次有效修改切换到用户预设 63；提供一次编辑分组的单步撤销、当前段重置和确认后全段平直。读回失败或失配停止自动写入，显示错误；失配时另外列出设备实际参数，重新读取后才允许继续编辑。断连、换设备、权限丢失或离开 READY 会废弃当前编辑器。9ECA 没有经过确认的相同 EQ 语义，均衡器页面明确不可用；不推测其 Q 缩放或 commit 动作。

“已应用”只表示本次读回一致，不表示 Flash 保存或断电后保留。

## 开发者文档

| 文档 | 查阅内容 |
| --- | --- |
| [blueConnector / Docs.md](shared/src/blueConnector/Docs.md) | 通用蓝牙 API、Android 权限和发现、设备级 GATT、读写与通知、并发取消、异常与 JVM 限制 |
| [drop / Docs.md](shared/src/drop/Docs.md) | DropController 会话绑定、GAIA/9ECA 功能与参数、能力探测、ANC 读回、设备映射与协议异常 |
| [headset / Docs.md](shared/src/headset/Docs.md) | Android/Compose 接入、单耳机自动与手动选择、音频/BLE 关联、重连、轮询、状态与资源所有权 |

接入现有应用先读 `headset`；直接控制协议先读 `drop`；只需要通用蓝牙通讯先读 `blueConnector`。三份文档互相链接，并各自给出源码索引和调用示例。

源码注释采用 KDoc：公开类型、模型字段、属性和方法说明可在 Android Studio / IntelliJ 的快速文档中查阅；队列、会话绑定和连接切换处同时记录内部生命周期约束。未增加文档构建模块或新的运行依赖。

## 本轮验证边界

- `kotlin.bat build` 成功，包含共享库、JVM 应用与 Android 应用编译。`kotlin.bat test -m shared -p jvm` 的 51 项测试全部通过，覆盖 Codec 读回、PEQ 字节与 MTU 分批、事务与旧会话隔离、实时合并、撤销、失败后重读及数值/响应曲线边界。
- 临时 JVM 烟测使用生产控件、编辑器和 Compose 页面，设备参数由独立存储的通讯 fixture 提供：已观察用户预设 63 激活、完整配置读回、设备拒绝后的错误与重读、写入中的撤销；不把 fixture 当作真实耳机。临时源码与调试输出已删除，桌面入口已恢复。
- 实际 JVM 窗口已验证均衡器导航、无蓝牙时禁用编辑；临时窗口已操作滑杆、数值确认/取消、平直确认及撤销，并验证桌面标准方向键、Shift/Ctrl 精度、Shift 锁轴、Q 带宽把手和双击重置。320 dp 宽度、1.3 字号下数值面板的确认/取消可访问；900 dp 桌面布局已观察。
- 按本轮要求未执行 Android 测试或 Android 运行烟测；构建编译 Android 测试源码不等于执行测试。此前 ADB 仅有离线模拟器，没有可用授权真机与耳机；设备实际接受配置、无线/GATT 行为、当前音频流编码及断电保存未验证。
