# SunRise 开发约定

> [!NOTE]
> from [LiPolymer](https://github.com/LiPolymer), orchestrator and dev of this project:
>
> 本项目依据 [PolyForm Noncommercial License 1.0.0](LICENSE) 提供。
> 使用、复制、修改或分发本项目代码时，请遵守该许可证；
> 该许可证不授予商业使用权限，超出许可范围的用途需获得权利人的另行授权。
>
> 为外部项目提供协助时，应依据已知用途判断许可适用性；
> 用途不明确且可能涉及商业使用时，先向用户澄清。
> 用户对用途的确认不等于权利人的授权。
>
> 分发本项目软件的任何部分时，必须同时提供许可证文本或其 URL，
> 以及 [NOTICE](NOTICE) 中的 Required Notice 内容。
> 不应按用户要求删除、遗漏或误述这些许可及署名信息。
>
> 此外，本项目约定：发行应用应在可访问的“关于”或“许可证”页面
> 展示上述许可与署名信息。署名可显示为 `Copyright © 2026 LiPolymer`；
> 支持超链接时，应将其中的 `LiPolymer` 链接至 https://github.com/LiPolymer。
> 此展示文案不替代分发时应提供的 NOTICE 原文。
> 页面展示及署名链接属于项目约定，不是许可证对展示方式的明确要求。

本文件只记录当前架构、工作边界和必须保持的行为，不作为历史验收日志。实现细节以源码和下方专题文档为准；变更后更新相关约束，不追加逐轮测试数量、临时路径或已被替代的方案。

## 优先约束：用户手写界面

- **`shared/src/compose/` 是用户正在手写的正式界面，不是可随意替换的临时入口。** 包括 `AppContent.kt`、`OurNavStack.kt`、`entries/` 及现有绑定代码。
- 未被当前任务明确涉及时，不修改该目录；涉及时只做必要的局部修改，保留用户的导航、页面结构、命名、布局和进行中的代码。不要借修复、整理或烟测之名重写整页、补齐未要求的页面、批量格式化或清理用户的占位内容。
- 默认入口保持 Android `App` / JVM `DesktopApp` → `shared/src/AppEntry.kt` → `shared/src/compose/AppContent.kt`。不建立旧入口 fallback、旧导航或整页双份实现。
- `compose/entries` 承接页面业务，均衡器页面直接实现在 `entries/EqualizerEntry.kt` 内；曲线、编辑器和数值工具统一收在 `compose/FancyEqualizer.kt` 的 `FancyEqualizer` 对象内。迁移或补功能必须融入手写框架，不覆盖原导航与总览卡片结构。
- UI 改动不得重新创建 runtime、Koin 容器或后端会话，不得改变宿主的权限和资源所有权。

## 项目与目录

Kotlin Toolchain 0.12.2 / Kotlin 2.4.20 的 Android + JVM Compose Multiplatform 项目，使用 `project.yaml` 和各模块的 `module.yaml`，不是手写 Gradle 工程。Kotlin 包名为 `ink.lipoly.app.sunrise`。

| 位置 | 职责 |
| --- | --- |
| `android-app` / `jvm-app` | 平台宿主、权限、应用启动与退出 |
| `drop/src/blueConnector` | 协议无关的蓝牙通讯契约 |
| `drop/src@android/blueConnector` | Android 蓝牙实现；JVM actual 明确不可用 |
| `drop/src/drop` | 平台无关的 GAIA / 9ECA 控制器与参数数学 API |
| `shared/src/headset` | 应用单耳机连接、选择、音频/BLE 关联和电量轮询 |
| `shared/src/di` | Koin 模块、进程 runtime 和蓝牙资源 owner |
| `shared/src/presentation` | 宿主展示会话、连接协调器、目录操作及 EQ 会话所有权 |
| `shared/src/catalog` / `settings` | 离线目录、频响计算及共同设置契约 |
| `shared/src/compose` | 用户手写的正式界面、Navigation3 导航、主题与目录绘图，修改边界见上 |
| `shared/src/compose/FancyEqualizer.kt` | `FancyEqualizer` 对象内的 PEQ 曲线、编辑器和数值工具；完整页面位于 `compose/entries/EqualizerEntry.kt` |

`drop` 可独立消费，仅导出 coroutines，不依赖应用、Compose 或 Koin；`shared` 将它导出给宿主。不建立旧目录副本、兼容转发或别名。公开参数 API 为 `GaiaPeqParameters`，纯数学 API 为 `PeqBiquad` / `PeqHeadroom`，协议 binding 和 payload codec 保持 internal。保留 PolyForm Noncommercial 1.0.0 的 `LICENSE` / `NOTICE`；没有 Maven 发布配置。

## 构建与验证

Windows 项目根目录执行：

```bat
kotlin.bat build
kotlin.bat test -m drop
kotlin.bat test -m shared
kotlin.bat test -m shared -p jvm
kotlin.bat run -m jvm-app
kotlin.bat run -m android-app
```

- Android 需要 SDK 37，最低 API 24；运行需可用设备或模拟器。JVM 不注册蓝牙管理器或客户端，但仍初始化离线目录。
- Compose 1.12.1 对应的 Material3 固定为 `1.12.0-alpha03`，不要自动降回旧版本；旧版本曾导致桌面诊断输入框运行时崩溃。其他依赖与插件版本查 `module.yaml`。
- `-Xexpect-actual-classes` 用于 expect/actual 类；Android GATT 保留 API 24–32 的旧回调与写入分支，弃用抑制只放在旧 API 使用处。
- 验证范围与改动一致；只有编译、静态检查或 fixture 时，不能声称真实 UI、Android 设备、耳机 DSP 或 Flash 已验收。用户明确要求仅静态检查时，不运行应用或测试。
- 烟测使用隔离目录、Preferences 和零蓝牙 fixture；不要覆盖用户真实设置、目录、绑定或应用签名，不替用户向真实耳机提交 EQ。不得为了烟测覆盖手写入口；临时代码和数据不留在生产源码中。
- Android 宿主单测不直接调用真实 Android 资源 API；随包快照的真实 Compose 资源读取在 JVM 验证。测试只验证消费者可见行为，不钉死展示文案或源码写法；不要恢复已按用户要求删除的 `CatalogEqResponseTest.kt`。
- 公开类型、模型字段和方法使用 KDoc；队列、会话绑定和连接切换处记录生命周期约束。

## Runtime、DI 与生命周期

- Koin 使用普通 DSL，不使用注解扫描、ViewModel 或 Compose 注入。编译插件保持 `compileSafety=true`；Android、desktop、preview 使用显式 modules 列表创建隔离容器，模块属性每次返回新 `Module`，避免 singleton factory 缓存跨容器共享。
- Android `SunRiseApplication` 创建唯一进程级 `SunRiseRuntime`；Activity 只报告权限、创建/附着展示会话，销毁或重建不关闭应用耳机资源。`ApplicationBluetoothResources` 是唯一蓝牙 owner，显式关闭顺序为 `client.close()` → `bt.close()`。
- 不依赖 `Application.onTerminate`，不增加 Service 或后台保活；系统杀进程不保证优雅清理。Android 12+ 需要 `BLUETOOTH_CONNECT`，扫描另需 `BLUETOOTH_SCAN`，旧版 LE 扫描需要定位权限；由宿主申请。
- `ConnectionCoordinator` 拥有进程级连接策略：权限未报告或目录首次加载中不接受候选，授权且加载结束后启动一次自动连接。快照/筛选变化只更新 predicate，重复权限报告或页面附着不替换当前 epoch；缺失权限恢复或显式 retry 才重新启动。
- 桌面正常退出与 shutdown hook 共用 suspend `runtime.close()`：停止协调器 → 取消初始化 → 排空 Catalog 与应用 scope → 关闭 Koin 蓝牙 owner。
- 每个宿主从 runtime 创建独立 `PresentationSession`。`HeadsetPresentationController` 管候选、事件、能力读回与动作；`CatalogOperations` 管文档选择、导入确认与显式下载；`EqSessionOwner` 按 GAIA controls 的引用身份复用/更换编辑器。
- 展示会话按 EQ → 目录操作 → 耳机展示 → 子 scope 释放，不关闭进程服务或宿主 picker。关闭后的旧异步结果不得重新发布确认框或成功通知；已开始的 Catalog 原子提交仍胜出。

## Compose 接入契约

`AppContent` 显式接收后端接口，不在页面中做 Compose/Koin 注入：

| 参数 | 用途 |
| --- | --- |
| `catalog` | `Catalog.state`、产品/频响查询 |
| `client` | 可空 `HeadsetClient`、状态与协议控件；JVM 为 null |
| `session.headset` / `session.catalog` / `session.eq` | 耳机动作、目录操作、EQ 编辑器所有权 |
| `settings` / `onSettingsChange` | 当前设置及宿主写回 |
| `missingPermissions` / `onRequestPermissions` | 缺失权限及宿主授权回调 |
| `dynamicColorAvailable` | 平台动态色能力 |

StateFlow 用 `collectAsState()` 订阅。`compose/ParamEqBinding.kt` 的 `rememberParamEqEditor(client, headsetState, session.eq, missingPermissions)` 留在导航外的根级：权限不足、断连或 GAIA 未 READY 时解绑，相同控件复用编辑器。页面接收 `editor` 和 `editor.state`；页面切换不关闭 editor、session、client 或 Catalog，宿主负责释放会话。

`OurNavStack.Route` 的 Overview / Equalizer / Settings 是底栏主路由；Catalog / Diagnostics 是非 `INavNode` 子路由，诊断隐藏底栏。Scaffold padding 只施加于 NavDisplay。通知、导入确认、候选与源/目标选择留在根级；目录“用作参考”先出栈再进入 EQ，不留下完成的目录路由。`EqualizerEntry` 直接实现均衡器页面及页面私有控件，映射参考资料与设置，不拥有 editor。`EqSessionOwner` 引用 `FancyEqualizer.ParamEqEditor`，仍按 GAIA controls 身份管理编辑器生命周期；总览控制规则保留在 `presentation/OverviewRules.kt`，不依赖页面。

`EqualizerEntry` 复用 `FancyEqualizer.ParamEqCurve`；编辑器、编辑状态、发送方式和数值工具均属于 `FancyEqualizer`，成员 API 保持 internal，不另建完整 PEQ 屏幕、旧包兼容转发或别名。对象只组织声明，不持有全局编辑器或设备会话；每个 `ParamEqEditor` 保持独立状态和串行 worker。正式应用文案固定中文，不恢复语言选择、`UiLanguage`、`english` 参数或 `tr` / `t` 双语函数；产品名、协议术语、上游错误及目录 `languageType` 保留原值，目录匹配与排序优先 `zh-CN`。

## 蓝牙与控制边界

- `BtManager` 按规范地址保存稳定 `BtDevice`，每台设备独立管理 GATT。`OnDiscovered` 只表示枚举/扫描观测，不证明连接、品牌匹配或协议可用。
- 显式 `device.gatt.connect()` 后，通过 `DropController(device).awaitReady()` 初始化。控制器关闭不关闭 GATT；不同设备会话隔离，旧帧和旧控件不能影响新会话。不保证同一设备上的重复控制器并行控制同一协议。
- 应用仍为单耳机策略：0 台音频候选等待，1 台尝试，多台要求选择；依次尝试缓存端点、音频地址、同名已配对 LE/DUAL、LE 扫描。同名不是物理设备关联证明。
- 连接阶段、音频身份与错误在 `HeadsetState`，协议、能力、电量与 ANC 在 `state.controls`。电量轮询由应用 facade 拥有，不下放控制器。
- ANC 和 LDAC/LC3/LHDC 设备端开关采用 SET 后 GET 确认；失配保留实际值，无法验证则清除未知值。编码开关不代表当前音频流实际编码。

## PEQ：不可破坏的行为

- Bluetrum 段数从设备读取，不硬编码五段；验证路径固定 Bell/峰值，写入 filter 字节为 0，该路径按峰值读取。通用控件支持的其他滤波器编号不能直接写到 Bluetrum。未知完整字节 29 必须拒绝读取，不取低四位、不猜映射。9ECA EQ 语义未经确认，明确不可用。
- 保留 raw 参数精度、对数频率和 48 kHz RBJ 模型。模型不是声学测量，也不保证与厂商未量化输入或耳机 DSP 逐位一致。
- 实时模式最多每 150 ms 发送最新完整配置，松手刷新最后一笔，写入串行，相同草稿不重复发送，不自动 EQ 读回。失败保留原错误、停止后续写入并禁用编辑，完整重读后恢复；重读替换草稿，清除撤销与已发送记录。断连、换设备、权限丢失或离开 READY 废弃编辑器。
- 手动提交模式的编辑、松手、撤销、重置和平直只改草稿；只有“提交一次”发送完整快照，刚读取的原样配置也允许提交一次。排队/发送期间冻结编辑与重复提交；成功后直到再次编辑或重读才可再提交。方式切换不隐式发送，重读保留方式，新编辑器默认实时。
- 写入顺序固定：查询段数 → 无条件选择 USER63 → 等待 100 ms → 批量参数。没有当前预设查询或写后切换；所有分包使用同一新 header，频率/Q/单段增益不变，按实际链路容量分包。
- 有经典 RFCOMM 绑定时，激活与参数写入走经典链路，BLE 保留读取和控件；经典写入失败不得回退 BLE。无经典通道时仍使用 BLE。实机曾证明同字节 BLE 写入导致单侧无声，而官版经典写入正常，不得仅凭字节一致改回 BLE。
- RFCOMM 使用 SPP，`GaiaRfcomm` 帧为 `FF 04 00 <GAIA 负载长度>` + 标准 GAIA 帧。失败时关闭可疑通道；单帧最多三次尝试，重连前等待 150 ms，不重发已成功帧。入站丢弃尽力而为，读取失败不冒充写入失败。
- 每次发送按最新量化 raw 参数重算前置衰减：48 kHz、20–20000 Hz 的 768 个对数点计算级联峰值，负补偿向下舍入到 0.1 dB 后再减 0.1 dB，无提升时不做正向放大。超出 signed16 或非有限响应时，在任何命令前拒绝。它不是 limiter 或绝对防削波保证。
- “已发送”仅代表传输完成，不证明设备接受、DSP 生效或 Flash 保存；不能用继续加衰减掩盖爆音。
- 保留节点拖动、滚轮 Q、带宽把手、键盘微调及单参数输入。触屏双指横向张开使 Q 降低、合拢使 Q 升高；单指可升格双指，仅继续修改所选段 Q，剩一指后不恢复节点拖动，全部松手后再开始。组合手势共用一次撤销，不改变发送策略。

### 发送诊断与实机安全

默认关闭的跟踪用 `adb shell setprop log.tag.SunRiseGatt DEBUG` / `log.tag.SunRiseRfcomm DEBUG` 开启，`adb logcat -v threadtime -s SunRiseGatt:D SunRiseRfcomm:D "*:S"` 查看。属性首次读取后缓存，设置后需重启应用；关闭将属性设为 `INFO`。

GATT 用 `sid` / `wid` 关联 `tx-start` 的完整 PDU、MTU 与匹配回调状态；原生接纳/成功不是 DSP 已应用。经典 EQ 提交时 BLE 只应有段数查询 `001D0A04`，不应出现参数写入 `001D0A06`。RFCOMM 日志记录连接、写入和失败原因链，不含完整 payload，但可能含对端地址；原始 PDU 也可能敏感，分享前检查。

实机对照先由官版恢复正常配置，停播并退出官版，避免两个客户端同时控制；只做一次手动提交，低音量短暂检查，爆音立即停播，不重复提交或切回实时。没有用户要求，不安装/覆盖应用或执行真实 EQ 写入。

## 离线目录与频响

### 数据与事务

- 启动优先有效本地快照，否则读内置 `shared/composeResources/files/moondrop-catalog.snapshot.json`；损坏/旧格式提示后回退，不自动修复、迁移、联网或写盘。浏览、筛选、查询和选择参考均不联网。
- 快照为版本 3 `sunrise-moondrop-catalog` UTF-8 JSON，不读取/导入版本 1/2 或旧 `sunrise-moondrop-bt`。默认导出名 `sunrise-moondrop-catalog.json`；导出保留完整快照字节，不合并设置、地址、绑定或 EQ 草稿。
- `catalogue` / `responseLibrary` 嵌套两份原始响应对象，保留未知字段、tags 和数组顺序；物理产品保留非空原始类型，频响库投影为 `Response`，不推断型号、语言、芯片、EQ 段数或蓝牙能力。物理产品在前，Response 按原序追加。
- `responseFiles` 保留 path、SHA-256、encoding 和原文 lines，不用 Base64，不丢注释/相位/解析失败原文。优先 UTF-8，非法 UTF-8 用 ISO-8859-1 可逆映射；只按 LF 分行，保留 CR 和末尾空行，以还原原始字节。哈希不证明厂商签发。
- 跨来源 UUID 大小写不敏感冲突、缺失/额外资产、坏哈希、超限或未知版本拒绝整个快照；共享路径只存一份资产，不合并不同 UUID。
- 只有显式拉取访问无 `ProductType` 参数的 `/api/v1/products/all`、`/api/v1/responselib/allwithtag` 与用户选定 CDN；下载全部不同引用路径，最多四并发，不重试、不自动切换 CDN。两份元数据、全部资产验证及原子落盘成功才切换；失败或提交前取消保留旧数据。导入先预览、确认后完整替换。
- `Catalog` 是实例隔离的 internal 服务，`Catalog.kt` 内 private `Repository` 是唯一事务后端。重复 init 不执行工厂、不重载或覆盖导入；close 排空读取、下载和提交后才允许 reinit，保留同一 StateFlow 及最后完整快照的查询/导出。每次 init 的 fetcher 由 Catalog 独占关闭，不注册全局 singleton 或同步 onClose。
- 同步查询无 I/O/采样：`getAll()` 保留产品原序；`getHaveResponse()` 现场筛选 Ready 曲线；`getBluetooth()` 现场筛选严格 `type == "BT"`，不要求曲线。后二者不预分类/缓存；UUID/名称索引在快照建立时生成。UI 和设备 predicate 使用显式 snapshot，更新期间继续服务旧完整版本，避免跨版本混用。
- 编解码、匹配、采样、曲线计算及平台 I/O 保持分文件，纯计算用包级函数，不经生命周期服务转发。

### 匹配、参考与持久化

- 蓝牙筛选和自动参考仅使用严格 `type == "BT"` 的精确名称匹配，忽略名称大小写及首尾/连续 Unicode 空白，不匹配 model、前缀或近似型号；目录匹配不证明协议支持。筛选变化不断开当前目标。
- 浏览、手选源参考、目标参考和比较允许全部类型。自动源参考先用有效手动 UUID 绑定，再在同名 BT 中优先可解析曲线，按 `zh-CN`、空语言、其他语言及 UUID 排序。UUID 消失提示并退回自动匹配，不按名称迁移。
- 源参考绑定按音频地址保存，不用 BLE 端点；未连接/JVM 时仅作会话预览。独立目标 UUID 与显示开关跨设备持久化：隐藏保留选择，清除删除 UUID，丢失提示重新选择；不改源绑定、不自动拟合或发送 EQ。
- Android 使用 `filesDir/catalog/active.json` 和 SAF，不申请广泛存储权限；JVM 使用 `${user.home}/.sunrise/catalog/active.json` 和原生文件对话框。`settings` 的 StateFlow 契约、平台 `AndroidUiSettingsStore` / `JvmUiSettingsStore`、原设置键及写入规则保持不变；目录偏好持久化，JVM 原外观设置仍为进程内行为。
- 维护内置基线只手动运行 `python tools/fetch_moondrop_catalog.py`，海外源加 `--cdn overseas`，不挂到普通构建或启动流程。

### 同图曲线

源参考虚线、DSP 预测实线、可选目标虚线及淡化 EQ 响应在同一可交互图内；EQ 用左轴，源/预测/目标共独立右轴，不能把 SPL 当节点增益。预测来自量化草稿和 48 kHz RBJ，默认包含自动前置增益；显示开关不改草稿或发送配置，EQ 响应本身不含前置衰减。没有草稿不制造预测，无法解析不画平直替代线。

覆盖 500 Hz 的参考各自减去自身 `SPL(500 Hz)`；源未覆盖则显示原始 SPL，不外推。目标形状叠加要求源与目标均覆盖 500 Hz，只画目标自身覆盖范围；条件满足时隐藏源仍可独立显示目标。右轴按所有可见频响共同极值缩放，隐藏目标不参与；不可表示的数值跨度显示原因。预测不再次归一化，资料频响不是当前设备实测，曲线接近不保证听感相同。

## 专题文档

| 文档                                                      | 何时阅读                                                  |
|-----------------------------------------------------------|-----------------------------------------------------------|
| [headset / Docs.md](shared/src/headset/Docs.md)           | 接入应用、单耳机选择、音频/BLE 关联、重连、轮询和所有权   |
| [drop / Docs.md](drop/src/drop/Docs.md)                   | 直接控制 GAIA/9ECA、会话绑定、能力、参数和协议异常        |
| [blueConnector / Docs.md](drop/src/blueConnector/Docs.md) | 通用蓝牙、Android 权限、设备级 GATT、队列/取消和 JVM 限制 |

涉及某层时先读对应文档；不要把完整 API 示例、抓包历史或验收流水重新复制到本文件。
