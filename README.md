# SunRise

> [!caution]
> <img src="https://lipoly.ink/assets/badges/llmWarn.svg" alt="This project contains unaudited AI generated code" width="200">
> 
> under construction

Kotlin Toolchain 0.12.2 项目，包含 Android 应用、JVM 桌面应用和 Android/JVM 共享库。`shared/src/blueConnector` 是协议无关的蓝牙通讯契约，`shared/src/drop` 是平台无关的 GAIA / 9ECA 控制器，`shared/src/headset` 是本应用的单耳机连接与选择策略；`shared/src/compose` 是两平台共用的概览、设置和诊断界面。平台蓝牙实现位于 `shared/src@android/blueConnector`，JVM actual 明确不可用。Kotlin 包名仍为 `ink.lipoly.app.sunrise`。

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

## 开发者文档

| 文档 | 查阅内容 |
| --- | --- |
| [blueConnector / Docs.md](shared/src/blueConnector/Docs.md) | 通用蓝牙 API、Android 权限和发现、设备级 GATT、读写与通知、并发取消、异常与 JVM 限制 |
| [drop / Docs.md](shared/src/drop/Docs.md) | DropController 会话绑定、GAIA/9ECA 功能与参数、能力探测、ANC 读回、设备映射与协议异常 |
| [headset / Docs.md](shared/src/headset/Docs.md) | Android/Compose 接入、单耳机自动与手动选择、音频/BLE 关联、重连、轮询、状态与资源所有权 |

接入现有应用先读 `headset`；直接控制协议先读 `drop`；只需要通用蓝牙通讯先读 `blueConnector`。三份文档互相链接，并各自给出源码索引和调用示例。

源码注释采用 KDoc：公开类型、模型字段、属性和方法说明可在 Android Studio / IntelliJ 的快速文档中查阅；队列、会话绑定和连接切换处同时记录内部生命周期约束。未增加文档构建模块或新的运行依赖。

## 本轮验证边界

- `kotlin.bat build` 成功；JVM、Android 宿主各 45 项共享测试通过，包含队列取消/排空、双设备回复匹配、换会话隔离、ANC 读回、单耳机策略、Android 设置持久化和 JVM 工厂不可用。
- 独立 JVM main 显式驱动两个生产控制器：实际电量 `A=42/43 B=81/82`；关闭 A 控制器后两会话仍 CONNECTED、B ping 成功；A 重连后 `64/65`，旧回复被隔离。通讯 fixture 仅用于烟测，临时模块已删除，交付仍仅三个模块。
- 实际 JVM 窗口已观察未知电量“—”、平台不可用提示、禁用刷新/连接/GAIA 动作，以及可访问的设置和诊断页面。
- ADB 只有离线模拟器，没有可用授权真机与耳机；Android 无线/GATT、权限交互和多设备选择界面未实测。
