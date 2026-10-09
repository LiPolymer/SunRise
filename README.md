# SunRise

> [!caution]
> <img src="https://lipoly.ink/assets/badges/llmWarn.svg" alt="This project contains unaudited AI generated code" width="200">
> 
> under construction

Kotlin Toolchain 0.12.2 项目，包含 Android 应用、JVM 桌面应用、应用共享库 `shared` 和可独立消费的 Android/JVM KMP 库 `drop`。`drop/src/blueConnector` 是协议无关的蓝牙通讯契约，`drop/src/drop` 是平台无关的 GAIA / 9ECA 控制器，`shared/src/headset` 是本应用的单耳机连接与选择策略。当前共享界面入口位于 `shared/src/compose`，可复用 PEQ 控件位于 `shared/src/controls/peq`；`composeLegacy` 保留旧概览、设置、目录和诊断界面，不再作为默认入口。平台蓝牙实现位于 `drop/src@android/blueConnector`，JVM actual 明确不可用。Kotlin 包名仍为 `ink.lipoly.app.sunrise`。

## 运行

Windows 在项目根目录执行：

```bat
kotlin.bat build
kotlin.bat test -m drop
kotlin.bat test -m shared
kotlin.bat run -m jvm-app
kotlin.bat run -m android-app
```

Android 构建需要 Android SDK 37；运行需要已连接的设备或模拟器。Android 首次启动请求蓝牙权限；宿主继续管理权限和连接生命周期。当前新入口仅显示标题、连接阶段、目录条目数、可用时的 EQ 段数及缺失权限时的授权按钮，没有迁入旧导航和业务页面。以下概览、设置、目录、诊断与完整 EQ 页面的说明对应保留的旧界面或独立控件，不表示新入口已经展示这些页面。

JVM 桌面运行同一新入口，但不注册蓝牙管理器或客户端，显示蓝牙不可用；离线目录仍初始化。设置服务和持久化规则保持不变，新入口尚未提供设置编辑界面。

应用界面固定为中文，不再跟随系统语言，也不提供语言选择。新入口、旧页面、PEQ 和展示层通知直接使用中文文案，已删除 `UiLanguage`、`english` 参数及 `tr` / `t` 双语辅助函数。产品名称、协议术语、上游错误信息和目录 `languageType` 原始元数据保留；目录自动匹配与浏览排序固定优先 `zh-CN`。Android 不再读写旧 `language` 设置键。

中文单语言验收：移除临时入口后 `kotlin.bat build` 成功，覆盖 JVM/Android 生产代码和测试源编译；`kotlin.bat test -m shared -p jvm` 的 177 项测试通过。零蓝牙桌面烟测将 JVM Locale 设为 `en_US`，检查新入口及旧概览、设置、PEQ、目录和诊断页面，应用文案仍为中文，设置页不再显示语言选项。烟测未运行 Android、联网拉取或向真实耳机发送命令；临时入口和截图已清理。

Android `SunRiseApplication` 创建唯一进程级 `SunRiseRuntime`，Activity 只报告权限和附着展示层，销毁/重建不关闭应用耳机资源。`ApplicationBluetoothResources` 是唯一蓝牙 owner，显式关闭时先 `client.close()` 再 `bt.close()`；生产不依赖 `Application.onTerminate`，不增加 Service 或后台保活，系统杀进程不保证优雅清理。Android 12+ 需要 `BLUETOOTH_CONNECT`，扫描还需要 `BLUETOOTH_SCAN`，旧版 LE 扫描需要定位权限；权限由消费者宿主申请。原音频→BLE 关联、经典 RFCOMM EQ 路径与设置键保持不变。

编译说明：共享模块用 `-Xexpect-actual-classes` 确认 Kotlin `BtHost` expect/actual 类仍处于 Beta；Android GATT 为兼容 API 24–32 保留旧版回调及写入分支，弃用抑制局限于旧 API 使用处。Material3 明确固定为与 Compose 1.12.1 对应的 `1.12.0-alpha03`，避免自动推断旧版本导致桌面诊断输入框运行时崩溃。

## 手动重写 Compose 界面

入口链路：Android `App` / JVM `DesktopApp` → `shared/src/AppEntry.kt` → `shared/src/compose/AppContent.kt`。直接修改新 `AppContent` 的内容即可；无需重新创建 runtime、Koin 容器或后端会话。两平台复用原主题；旧业务页面保留，语言选择及双语文案分支已移除。

新入口显式接收以下接口，不依赖 Compose/Koin 注入：

| 参数 | 用途 |
| --- | --- |
| `catalog` | `Catalog.state`、产品/频响查询、目录数据 |
| `client` | 可空 `HeadsetClient`；`state`、协议控件；JVM 为 null |
| `session.headset` | 连接选择/重试、操作执行、候选与通知状态 |
| `session.catalog` | 拉取、取消、导入确认、导出及操作状态 |
| `session.eq` | 按 GAIA 控件引用身份管理编辑器 |
| `settings` / `onSettingsChange` | 当前设置及写回宿主设置服务 |
| `missingPermissions` / `onRequestPermissions` | 缺失权限及宿主授权回调 |
| `dynamicColorAvailable` | 平台动态色能力 |

StateFlow 用 `collectAsState()` 订阅。`compose/ParamEqBinding.kt` 的 `rememberParamEqEditor(client, headsetState, session.eq, missingPermissions)` 在根级绑定 EQ：权限不足、连接失效或 GAIA 未就绪时解绑；相同控件复用同一编辑器。把绑定留在导航外，页面只接收 `editor` 和 `editor.state`；不要在页面切换时关闭 editor、session、client 或 Catalog。宿主仍负责释放会话。

复用 `ink.lipoly.app.sunrise.controls.peq` 下的 `ParamEqScreen`（完整控件）或 `ParamEqCurve`（曲线），编辑状态和数值工具分别在 `ParamEqEditor.kt`、`ParamEqValues.kt`。API 保持 `internal`，供 `shared` 内的新 Compose 界面使用；旧包没有兼容别名。

本次切换验收：删除临时烟测入口后，`kotlin.bat build` 成功；`ParamEqEditorTest`、`ParamEqValuesTest`、`EqSessionOwnerTest` 在 JVM 和 Android 宿主各 58 项全部通过。实际 JVM 新入口显示蓝牙不可用和 155 条离线目录；迁出的 PEQ 在独立零蓝牙烟测窗口绘制源/预测/目标曲线，鼠标拖动更新草稿，手动模式读取 1 次、写入 0 次。IDEA MCP 已检查新入口和迁出控件并解析绑定符号；新入口的设置与动态色预留参数尚未用于最小界面。临时源码、窗口和隔离数据已清理，未运行 Android 应用或向真实耳机提交 EQ。

## 通讯与控制

通讯包仍为 `ink.lipoly.app.sunrise.blueConnector`；源码从 `shared` 整目录迁入独立 `drop`，包名与 `BtManager`、`BtDevice`、`GattSession`、`BtHost`、`BtPermissions`、`createBtManager` 等公开签名不变。直接消费者依赖 `drop`；`shared` 将它导出给应用宿主，不保留旧目录副本或兼容转发。

通用 `BtManager` 以规范地址保存稳定 `BtDevice`，每台设备独立管理 GATT。`OnDiscovered(device, sender)` 只表示系统枚举或扫描观测到了设备，不表示 GATT 已连、品牌匹配或协议可用。

调用方显式 `device.gatt.connect()`，再用 `DropController(device).awaitReady()` 初始化控制。控制器关闭不关闭 GATT；不同设备可同时使用各自的控制器，旧会话帧和旧控件引用不能影响新会话。不保证同一设备上重复控制器并行控制同一协议。

应用继续使用单耳机界面：连接阶段、音频设备身份与错误在 `HeadsetState`，协议、能力、电量与 ANC 在 `state.controls`。0 台音频候选等待，1 台尝试，多个要求选择；缓存端点、音频地址、同名已配对 LE/DUAL、LE 扫描依次尝试。同名不是物理设备关联的证明。ANC 一次 SET 后依据读回确认，失配保留实际模式，无法验证时清除未知值；电量轮询由应用 facade 拥有，而不是控制器。

## Codec 与 ParamEQ

概览的音频编码分组提供 LDAC、LC3、LHDC 设备端开关；每次修改后查询设备确认，读回失配显示实际结果，查询失败显示未知。这些开关不代表当前音频流实际使用的编码。

底部“均衡器”提供 GAIA Bluetrum 参数均衡器，段数来自设备读取，不硬编码 Ultra 的五段。当前 Bluetrum 验证页面固定 Bell / 峰值，类型选择禁用，批量写入 filter 字节为 0；通用控件仍保留低/高架、低/高通及 Bypass 的选择和曲线能力，不把这些通用 GAIA 编号直接写入 Bluetrum。频率使用对数刻度，增益和 Q 按协议原始精度保留。手机以大滑杆、微调按钮和单参数数值面板编辑；桌面还支持节点拖动、滚轮调整 Q、带宽把手、Shift 锁轴、方向键微调及双击重置。方向键默认频率约 1%、增益 0.1 dB；Shift 使用粗调，Ctrl 使用精细调。曲线仅为固定 48 kHz 的 RBJ 参数响应估算，不是耳机声学测量。

手机和平板还支持曲线触屏编辑：先选择频段，单指拖节点调整频率/增益，双指在绘图区内横向张开使带宽变宽（Q 降低），合拢使带宽变窄（Q 升高）。也可从单指节点拖动升格为双指，只继续修改该段 Q；抬起其中一指后不会恢复节点拖动，全部松手再开始下一次操作。组合手势共用一次撤销。曲线上方实时显示所选段 Q，下方提示手势；Q 滑杆与精确数值输入仍作为单指/无障碍替代。触屏新增输入不改变实时/手动发送策略。

默认实时编辑最多每 150 ms 发送最新完整配置，松手刷新最后一笔；写入串行执行，不自动 EQ 读回。发送完成显示“已发送”，当前曲线是已发送草稿，不是设备实际配置；每次结构化写入先选择用户预设 63，约 100 ms 后再写入全部参数。提供一次编辑分组的单步撤销、当前段重置和确认后全段平直。实时模式下相同草稿不会因松手、刷新发送队列或重复编辑再次下发。发送失败保留原始错误、停止后续写入并禁用编辑，手动重新读取后才可继续。初次加载和“重新读取”仍读取完整实际配置；手动读取会替换草稿并清除撤销与已发送记录。断连、换设备、权限丢失或离开 READY 会废弃当前编辑器。9ECA 没有经过确认的相同 EQ 语义，均衡器页面明确不可用；不推测其 Q 缩放或 commit 动作。

“已发送”只表示传输完成，不证明设备接受、DSP 生效或 Flash 保存。编码开关的 SET 后 GET 确认不变。EQ 写入先查询段数，再发送预设选择、等待 100 ms、最后批量写入参数；不再查询当前预设，也不在写入后切换，顺序镜像官版社区应用（官版为 setEQ(63) 后由 100 ms 定时器提交，抓包间隔约 105 ms）。当绑定带有经典 RFCOMM 通道（音频身份与 BLE 端点不同地址时的 SPP 记录）时，激活与参数写入走经典链路，BLE 只保留读取与控件；此时经典写入失败不回退 BLE。预设选择不是 EQ 参数验证。

Bluetrum 峰值写入类型码仍为 0，该路径下类型 0 按峰值读取；150 ms 节流不变。用户预设激活改为在批量写入前无条件发送（镜像官版社区应用）。实机对照显示：字节、顺序与间隔都与官版一致后，经 BLE GATT 写入同一组 43 字节仍使设备单侧无声，而官版经经典 RFCOMM 写入同样字节正常，因此结构化写入改用经典链路（无经典通道时仍用 BLE）。每次发送前按整组最新 raw 参数重算前置衰减，不沿用旧 header：48 kHz、20–20000 Hz 的 768 个对数频点计算级联响应，取最大提升，负补偿向下舍入到 0.1 dB 后再减 0.1 dB；没有提升时不做正向前置放大。所有分包使用同一个新 header，频率/Q/单段增益保持原值。补偿超出 signed16 或频响非有限时，在任何命令前拒绝。页面显示下次发送或已发送的实际编码增益；响应曲线不包含前置衰减。使用模型中已经量化的参数，不能宣称与官版未量化浮点输入或耳机 DSP 逐位相同；离散峰值补偿不是 limiter 或任意音源的绝对防削波保证。未知完整字节 29 仍阻止手动读取，不取低四位、不映射为 13 或 0。

### EQ 发送诊断

均衡器顶部可开启“手动提交（诊断）”：拖动、数值输入、松手、撤销、重置和平直只修改草稿，不发送；点击“提交一次”才发送完整快照，按实际 MTU 分包。刚读取且参数未改变时也可显式提交一次，用于同配置对照。排队/发送时禁用手动编辑及重复点击；成功后保留“已发送”，直到进一步编辑或重新读取才可再次提交。未提交草稿可用“重新读取（丢弃草稿）”撤销。发送方式只在无手势的已读取/已发送状态切换，不隐式提交；重新读取保留方式，新编辑器默认实时。**该入口用于采集持续爆音的证据，不表示爆音已修复。**

Android 原生 GATT 跟踪默认关闭，用 Android SDK 的 `adb` 开启：

```sh
adb shell setprop log.tag.SunRiseGatt DEBUG
adb logcat -v threadtime -s SunRiseGatt:D "*:S"
```

`tx-start` 在队列实际启动原生写入时记录完整 `pdu`、`bytes`、实际 `mtu`、特征 `uuid`；`tx-return` 区分原生接纳/拒绝；`tx-complete` 记录匹配回调的 GATT `status`。按进程内会话 `sid` 和写入 `wid` 关联，`tNs` 是开机以来的单调纳秒时间。`mtu-complete` 记录实际协商值；会话终止记录正在等待的写入。GAIA 的完整 TX 可直接区分参数写入 `00 1D 0A 06`、预设切换 `00 1D 0A 03 3F` 和预设控件查询 `00 1D 0A 02`（写入路径不再发送后者）的顺序；一次提交可以包含多个 GATT 包。**结构化 EQ 写入走经典 RFCOMM 后，提交时这里只应出现段数查询 `001D0A04`，不再出现 `001D0A06`**；没有抓取 HCI/空口，不把原生接纳或 GATT 成功当成 DSP 已应用。

经典通道写入失败默认不记录，用同一方式开启：

```sh
adb shell setprop log.tag.SunRiseRfcomm DEBUG
adb logcat -v threadtime -s SunRiseRfcomm:D "*:S"
```

`tx-fail` 记录对端地址、字节数与平台异常类型/消息（不含完整 payload）。属性在应用进程首次读取后即被缓存，`setprop` 之后必须重启应用才生效：`adb shell am force-stop ink.lipoly.app.sunrise`。关闭用 `adb shell setprop log.tag.SunRiseRfcomm INFO`（`log.tag.SunRiseGatt INFO` 同），手机重启也会清除这两个非持久属性。

跟踪不增加协议命令、不记录 MAC/设备名称，但原始 PDU 仍可能敏感，分享前检查。停播或低音量对照，出现爆音停止播放；先由官版恢复正常配置，再单次对照，不能靠继续加衰减掩盖问题。

Windows / PowerShell 单次对照：

1. 官版写回同一社区配置，暂停播放并退出官版，避免两客户端同时控制；不要改音频编码或另做 AutoEQ。
2. 项目根目录的终端 A 开启跟踪并保存文件；保持该终端运行：

   ```powershell
   $adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
   & $adb shell setprop log.tag.SunRiseGatt DEBUG
   & $adb logcat -T 1 -v threadtime -s SunRiseGatt:D '*:S' | Tee-Object -FilePath .\sunrise-eq-tx.log
   ```

3. 项目根目录终端 B 执行 `.\kotlin.bat run -m android-app`，默认 debug 安装/启动。打开均衡器，等待实际配置读取成功，再开启“手动提交（诊断）”；读取失败/未知 filter 29 时停下，不绕过校验。
4. 不修改任何频段，点击一次“提交一次”；待显示“已发送”后低音量短暂播放。若爆音立即暂停，不重复提交，不切回实时模式。
5. 终端 A 按 Ctrl+C，提供 `sunrise-eq-tx.log` 与“单次提交后正常/爆音/发送失败”的结果。先比较真实 TX、分包和预设顺序，再决定是否改变发送链路。初次单次对照尚未确认前不测试连续实时拖动。



## 离线产品与频响目录

1. **直接离线使用**：首次启动读取随包快照；之后优先使用有效本地快照。启动、翻页、筛选、查资料和选择参考都不联网。目录先保留所有物理产品，再按原始顺序追加频响库记录；库记录统一投影为 `type = "Response"`，不是蓝牙设备或协议能力声明。无法解析的原文仍保留并显示原因，不画平直替代线。
2. **选择耳机**：概览默认“仅显示目录设备”，自动候选与手动蓝牙列表仅使用 `type == "BT"` 的目录记录进行同一精确名称匹配；忽略名称大小写、首尾及连续 Unicode 空白，不匹配 `model`、前缀或近似型号。类型不忽略大小写，USB、WIRED 或其他类型的同名记录不会成为蓝牙匹配依据。“显示全部”也包含无名称、改名和未收录设备。列表显示隐藏数量、目录匹配和参考状态；目录匹配不是协议支持证明。改变筛选不会断开当前目标，下一次自动选择才使用新策略。
3. **浏览目录**：概览或设置进入包含所有类型的离线产品与频响目录，搜索 `name` / `model`，列表和详情保留每个 UUID，显示原类型和库 tags。Response 是本地频响库分类，目标曲线不是设备型号或当前设备实测。芯片、EQ 段数、增益/Q 范围和滤波器类型仅展示源值，缺失显示“未提供”，编辑与写入仍以实际设备探测和读回为准。详情可选择另一 UUID，在同图比较各自以 500 Hz 归一化的两条参考曲线，允许跨产品/频响库来源比较；无曲线或不覆盖 500 Hz 时明确禁用对比，不借用近似型号。
4. **绑定参考**：均衡器可从所有类型中手选完整产品 UUID、恢复自动匹配。连接时按音频地址保存绑定，不使用 BLE 控制端点地址；未连接/JVM 时仅作页面会话预览。自动选择先用有效手动绑定，再仅在 `type == "BT"` 的同名组中优先可解析曲线，其后按中文（`zh-CN`）、空语言、其他语言及 UUID 排序。更新后 UUID 消失会提示并退回自动匹配，不按名称迁移到其他记录。绑定不放宽设备筛选，也不加载 EQ 预设或发送命令。
5. **管理快照**：设置中的“拉取”才会访问不带 `ProductType` 参数的 [官方产品目录](https://cdn-service.moondroplab.tech/api/v1/products/all)、[带标签频响库](https://cdn-service.moondroplab.tech/api/v1/responselib/allwithtag) 与显式选择的中国/海外 CDN；一次下载两来源引用的全部不同频响路径，最多四个并发请求，不重试、不自动切换 CDN。两份元数据和全部原文验证及原子落盘成功后才替换旧快照；第二来源失败、任一资产失败或提交前取消均保留旧数据。导入先预览、确认完整替换，导出原始完整快照字节，不合并、不包含音频地址、绑定、设置或 EQ 草稿。

均衡器在**同一张可交互图**内显示源频响虚线、DSP 预测实线、可选蓝色目标参考虚线及淡化的 EQ 响应；左轴仍编辑 EQ 增益，源/预测/目标共用独立右轴，不把 SPL 当作节点增益。预测使用当前量化草稿和现有 48 kHz RBJ 系数，默认包含写入模型的自动前置增益；关闭该开关只改变预测，不改草稿或设备配置。没有编辑器/草稿时仅显示资料频响，不制造预测线；隐藏或无法解析源频响时保留原 EQ 图及操作。

参考覆盖 500 Hz 时共用 `SPL(f) − SPL(500 Hz)` 显示归一化；未覆盖时明确显示原始 SPL，不外推到未测频段。参考可能已包含调音，预测不是实测、不是绝对声压，“已发送”不证明已应用。坐标轴按绘图区容量生成刻度，数值跨度无法表示时给出显示原因，不用平直替代线。

均衡器的“目标参考频响”可从所有本地产品/频响库记录中独立选择、显示/隐藏和清除。目标 UUID 与显示开关在 Android/JVM 均持久化，跨设备保留；不修改按音频地址保存的源频响绑定，不自动拟合 EQ、不发送配置。隐藏保留选择，清除删除目标 UUID；更新后 UUID 消失会提示重新选择，不按名称或其他 UUID 静默替代。

形状对比要求源频响和目标都可解析且覆盖 500 Hz，各自减去自身 500 Hz SPL；目标仅画自己的资料覆盖范围，不外推。右轴按所有可见频响的共同极值缩放，隐藏的目标不参与范围计算；隐藏源频响后，满足上述条件的目标仍可独立显示。源或目标不覆盖 500 Hz 时不叠加目标，并显示原因，原 EQ 编辑不受影响。比较调音形状时可关闭“预测含自动前置增益”，避免整体电平偏移干扰；预测不另行归一化，不改变发送时的自动前置增益。测量条件、佩戴和音量可能不同，曲线接近不保证实际听感相同。

交换文件为版本 3 的 `sunrise-moondrop-catalog` UTF-8 JSON，内置资源为 `files/moondrop-catalog.snapshot.json`，默认导出名 `sunrise-moondrop-catalog.json`，使用缩进排版，不使用 Base64。`catalogue` 和 `responseLibrary` 直接嵌套两份原始响应对象，保留未知字段、频响库 tags 和数组顺序，但不保留上游 JSON 的空白排版。物理产品的非空字符串类型按原值保留；频响库保留 uuid/name/file/tags 并投影为 Response 产品，不猜测型号、语言、芯片或 EQ 段数。跨来源 UUID 不区分大小写冲突时拒绝整个快照；共享路径只保存一份资产，不合并不同 UUID。`responseFiles[]` 包含 `path`、`sha256`、`encoding` 和逐行可读的 `lines[]`，不丢弃频响注释、相位列或无法解析的原文。

频响文本优先使用 `encoding: "utf-8"`；不是合法 UTF-8 的文件使用 `iso-8859-1` 作可逆字节映射，不猜测厂商原始字符集，因此旧编码注释可能仍显示乱码。`lines` 只按 LF 分行：CR 保留在行尾，末尾空字符串保留文件最后的 LF，按 LF 连接并以指定编码还原后得到原始频响字节。SHA-256 校验这些频响字节；目录由结构和字段规则验证，不再保存上游目录字节的哈希。哈希不证明厂商签发，来源 URL/时间来自快照文件。未知版本、缺失/额外资产、重复 UUID、损坏频响哈希及超限文件拒绝导入。

版本 1/2 和旧 `sunrise-moondrop-bt` 格式不再读取或导入。旧本地快照会提示并回退新的完整内置数据，旧 active.json 不自动覆盖；只有显式拉取或导入有效版本 3 后才替换本地文件。初始化零联网、零写盘，不自动迁移或修复旧数据。

Android 使用应用私有 `filesDir/catalog/active.json` 和 SAF 文档选择器，仅新增网络权限，不要求广泛存储权限。JVM 使用 `${user.home}/.sunrise/catalog/active.json` 和原生文件对话框；目录筛选、源/目标显示、目标 UUID、预测前置增益开关及地址绑定持久化，原有外观设置仍保留进程内行为。损坏本地快照回退内置数据并提示，不自动联网修复；两者都不可用时可显示全部设备、导入或显式拉取。

目录业务入口为可注入的 `internal class Catalog`，每个实例独立拥有状态、依赖和事务生命周期。`SunRiseRuntime` 用 `CatalogEnvironment` 工厂执行一次 `catalog.init(...)`；同一实例重复 init 不执行工厂、不重载、不覆盖导入，close/reinit 保留同一个 StateFlow 订阅入口。`catalog.close()` 排空读取、下载及原子提交后才允许重初始化，关闭后仍可查询/导出最后完整快照。每次 init 的 fetcher 由 Catalog 独占关闭，未注册为全局 singleton 或同步 onClose。

同步查询 `getAll()` 返回物理产品后追加 Response 的原序列表，`getHaveResponse()` 返回全部类型中解析为 Ready 的产品，`getBluetooth()` 只返回严格 `type == "BT"` 的记录（不要求有曲线）；它们不是蓝牙发现或能力探测。`getProduct(uuid)` / `getResponse(uuid)` 从同一当前快照按 UUID 查询，未知记录/未声明曲线返回 null，解析失败保留 Unavailable 诊断。查询无 I/O、不采样；更新期间继续服务旧完整版本，成功提交后才切换。UI 渲染和同步设备 predicate 使用显式 snapshot 的匹配/参考/计算方法，避免跨版本混用。

`getHaveResponse()` 与 `getBluetooth()` 在每次调用时捕获当前快照并现场筛选，保留产品原序；初始化/载入时不预先分类，也不缓存分类结果。UUID 与名称索引仍在快照建立时生成。

`Catalog.kt` 内的 `private Repository` 仍是唯一事务后端；编解码、匹配、采样、曲线计算及平台 I/O 保持分文件。纯计算直接使用 `matchesCatalogDevice`、`resolveCatalogReference`、`orderedCatalogProducts` 等包级函数，不经生命周期服务转发。共同设置契约位于 `shared/src/settings`，使用 StateFlow；平台实现为 `AndroidUiSettingsStore` / `JvmUiSettingsStore`，原 SharedPreferences/Preferences 节点、键和写入规则不变。

Koin 4.2.2 使用普通 DSL，不使用注解扫描、ViewModel 或 Compose 注入。共享模块启用编译插件 1.2.1 的 `compileSafety=true`；Android、desktop、preview 均以显式 modules 列表创建隔离容器。模块属性每次返回新 `Module`，避免 Koin singleton factory 缓存跨容器共享；真实双容器烟测验证 A 导入/关闭/reinit 不影响 B 的导出和 GATT 控制。缺依赖 probe 产生 `KOIN-D001`，补齐后真实 resolve 成功；Toolchain 0.12.2 的富文本错误渲染器会崩溃，完整诊断由同版本编译器直连同插件取得，安全检查未关闭。

`ConnectionCoordinator` 拥有进程级连接策略：初始权限未报告/目录加载中接受零台，授权且首次加载结束后启动一次自动连接。快照/筛选变化只更新 predicate；重复权限报告和页面附着不替换当前 epoch。缺失权限恢复或用户显式 retry 才重新启动。桌面正常退出及 shutdown hook 共用 suspend runtime.close，先停止协调器、取消初始化、排空 Catalog 与应用 scope，最后关闭 Koin 蓝牙 owner。

展示层使用普通 Kotlin 控制器，不采用 ViewModel 或 Compose/Koin 注入。每个宿主从 runtime 创建独立 `PresentationSession`：`HeadsetPresentationController` 拥有候选、事件、能力读回与动作状态；`CatalogOperations` 拥有文档选择、导入确认与显式下载；`EqSessionOwner` 按 GAIA controls 的引用身份复用或更换现有 `ParamEqEditor`。页面切换不关闭 editor，控件失效或宿主释放才关闭。会话按 EQ → 目录操作 → 耳机展示 → 子 scope 的顺序释放，不关闭进程服务或宿主 picker。关闭后的旧异步结果不能重新发布确认框或成功通知；已开始的 Catalog 原子提交仍胜出。

维护随包基线时手动运行 `python tools/fetch_moondrop_catalog.py`；明确选择海外源用 `--cdn overseas`。脚本不挂到构建或启动流程中，普通构建不重新下载数据。

## 开发者文档

| 文档 | 查阅内容 |
| --- | --- |
| [blueConnector / Docs.md](drop/src/blueConnector/Docs.md) | 通用蓝牙 API、Android 权限和发现、设备级 GATT、读写与通知、并发取消、异常与 JVM 限制 |
| [drop / Docs.md](drop/src/drop/Docs.md) | DropController 会话绑定、GAIA/9ECA 功能与参数、能力探测、ANC 读回、设备映射与协议异常 |
| [headset / Docs.md](shared/src/headset/Docs.md) | Android/Compose 接入、单耳机自动与手动选择、音频/BLE 关联、重连、轮询、状态与资源所有权 |

接入现有应用先读 `headset`；直接控制协议先读 `drop`；只需要通用蓝牙通讯先读 `blueConnector`。三份文档互相链接，并各自给出源码索引和调用示例。

`drop` 不依赖应用、Compose 或 Koin，仅导出 coroutines；源码模块附带原始 `LICENSE` 与 `NOTICE`，沿用 PolyForm Noncommercial 1.0.0，不提供 Maven 发布配置。参数换算和验证通过公开 `GaiaPeqParameters`，无 I/O 数学估算通过 `PeqBiquad` / `PeqHeadroom`；协议 binding 与 payload codec 仍为 internal。只复制 `drop` 的独立 JVM 消费者已运行：1000 Hz、6 dB、Q=1 的中心响应为 `5.999999999999984 dB`，前置增益 raw 为 `-366`，非法 Q=0 被拒绝；JVM 工厂仍明确报告蓝牙不可用。

源码注释采用 KDoc：公开类型、模型字段、属性和方法说明可在 Android Studio / IntelliJ 的快速文档中查阅；队列、会话绑定和连接切换处同时记录内部生命周期约束。未增加文档构建模块或新的运行依赖。

## 此前发送诊断验证

- `kotlin.bat test -m shared -p jvm` 的 77 项测试全部通过；新增手动模式的零自动发送、原样快照显式提交、重复点击/旧唤醒隔离、排队/发送期间快照冻结、重读丢弃草稿及保留方式、失败后的显式恢复和旧编辑器关闭边界。此前实时节流、撤销、原始数值、全局前置增益、会话隔离和 Codec 读回测试继续通过。临时烟测源码删除、桌面入口恢复后，Android Release 与 JVM 应用构建成功。
- 临时 JVM 烟测使用生产 GATT 绑定、控件、编辑器和独立通讯 fixture：社区五段快照编辑/松手/flush 后零配置写入；重复点击只提交一组配置。MTU247 写入一个范围 0..4，MTU23 写入五个单段范围，全部 header 为 `-336`（`FE B0`，-5.6 dB），各段 raw 值完整保留。继续等待、再次 flush/submit 不追加写入；手动脏草稿重新读取后恢复实际存储快照且仍为手动模式。
- 实际 JVM Compose 窗口已观察默认实时方式、开启手动后拖动仍为零配置包/未发送草稿、双击提交后仅一个配置包与“已发送，未验证”、提交按钮禁用，以及 500 px 窄窗口标签/按钮可见。临时 fixture 不属于交付后端，源码已删除；它不证明真实耳机接受或无爆音。
- APK 位于 `build/tasks/_android-app_buildAndroidRelease/gradle-project-release.apk`。ADB 更新安装因现有应用签名不一致被拒绝，未覆盖现有应用；用户选择自行安装，随后停止设备安装操作。未验证本轮 Android 新界面、真实 TX/MTU 日志或实体耳机的单次提交。用户再次报告仍然爆音；本轮只提供发送证据采集入口，不声称修复，不替用户执行真实 EQ 提交，不更改前置增益公式/用户预设顺序。真实 TX、设备应用及断电保存仍待实机验证。

## 当前预设查询时序诊断验证

- `kotlin.bat test -m shared -p jvm` 的 78 项测试全部通过。新增回归在修改前失败：预设查询出错时参数已被写入；修改后查询失败零配置写入，失效元数据阻止直接重试，完整重读后可恢复。USER63/非 USER63、分包失败、取消、会话切换和编辑器行为继续通过。
- 临时 JVM main 驱动生产 GATT 绑定与控件，对照预设 63/2、MTU512/23。MTU512 的顺序分别为 `[4, 2, 6]`、`[4, 2, 6, 3]`，MTU23 拆为五个单段包；不存在批量完成后的 command2。两种预设下的完整 43 字节参数 PDU 都与此前实机 `wid=15` 逐字节一致，各分包 header 保持 `FF7C`（-132，-2.2 dB）。此次只移动已有查询，不改变参数、前置增益公式或写后按需激活步骤；是否激活改为依据写前预设。
- 临时烟测源码已删除，桌面入口恢复；`kotlin.bat build -m shared` 成功，含 Android 生产及宿主测试源码编译，`kotlin.bat build -m jvm-app -p jvm` 成功。未运行 Android 宿主测试、未构建或安装 APK、未替用户执行实机 EQ 提交。
- 实机结果：用户单次提交的顺序为段数查询 → 预设查询 → 43 字节配置写入，写后无查询；本次读回预设已是 63，未发送激活命令。参数包与上一轮逐字节一致，仍爆音且单侧无声。结论：写后预设查询不是爆音根因，预设切换也不是本次触发点；剩余差异集中在配置写入本身的传输层（写类型、前后 GET、命令间距）与尚未捕获的官版实机 TX。

## 官版抓包对照与激活顺序验证

- 用户开启蓝牙 HCI snoop 并用官版应用应用一次社区预设后导出 `btsnoop_hci.log`（约 132 秒，含 A2DP 音频）。解出：官版在**经典 RFCOMM**（L2CAP PSM 3，ACL handle `0x0006`，CID `0x4c`/`0x41`）上发送 GAIA `00 1D 0A 03 3F`，**105 ms 后**发送单帧 43 字节 `00 1D 0A 06`（payload `00 04 FE B0 …`），设备随后回 `0B 03 01` 与 `0A 81 3F`。全部写入均为带响应帧，且官版不等选择回执。同一抓包中 BLE（ATT handle `0x0013`/`0x0015`）只出现 EQ 读取（`0A 00/01/02/04/05`）无 EQ 写入；SunRise 走 BLE GATT，GAIA 字节与官版一致，因此传输链路是目前唯一未排除的结构差异。
- 生产改动：结构化 EQ 写入改为「查询段数 → 无条件选择 USER63 → 等待 100 ms → 批量写参数」，删除写前预设查询与写后切换；段数一致性检查保留。
- 验证：`kotlin.bat test -m shared -p jvm` 77 项全部通过（新增激活顺序与激活失败分支断言；删除已失效的“预设查询失败”测试）。临时烟测用官版抓包的真实 43 字节帧驱动生产绑定：命令顺序 `[4, 3, 6]`、激活到写入实测 102.5 ms、写出 PDU 与官版逐字节一致，且 `PeqHeadroom` 对同一组参数算出 -336（-5.6 dB）与官版数值完全相同。临时烟测源码已删除。
- 未验证：本次顺序改动尚未在实体耳机上单次提交；官版走经典链路而 SunRise 走 BLE，这一差异仍需实机对照才能判定。

## 经典 RFCOMM EQ 写入通道

- 实机复测：在字节、顺序与间隔都与官版一致（段数查询 → `00 1D 0A 03 3F` → 103 ms → 同一 43 字节 `00 1D 0A 06`）后，经 BLE GATT 单次提交仍使设备单侧无声；而官版经经典 RFCOMM 写入同一组字节正常。结论：该设备的 BLE GAIA 写入路径不能用于结构化 EQ。
- 新增通道：`blueConnector` 增加 `BtRfcomm`（Android 按 SPP UUID `00001101-…` 解析 RFCOMM 通道 1 并连接，系统栈负责 SDP、组帧、流控与校验；JVM 与其他平台为 null），`BtDevice.rfcomm` 暴露句柄。`drop` 增加 `GaiaRfcomm` 组帧：`FF 04 00 <负载长度>` 加标准 `00 1D` GAIA 帧，长度只计 GAIA 负载。`DropController` 在配置身份与端点不同地址时把该句柄交给绑定，`setParamEq` 因此把激活与批量参数写到经典链路，BLE 只保留段数查询、完整读取与控件。管理器关闭时同步释放通道。
- 验证：`kotlin.bat test -m shared -p jvm` 80 项全部通过。新增用例用官版抓包的真实 43 字节帧驱动生产绑定，断言经典链路收到的两帧为 `FF 04 00 01 00 1D 0A 03 3F` 与 `FF 04 00 27` 加官版帧、间隔 ≥ 90 ms、BLE 侧只剩段数查询；写入失败用例断言不回退 BLE、元数据失效、重读后 10 段拆成 7+3 两块（长度字段 53 与 25）。`kotlin.bat build -m shared` 与 `build -m jvm-app -p jvm` 成功。
- 实机结果（用户报告）：配置经经典链路写入成功，官版可读回；偶发 `Bluetooth Operation Failed: write RFCOMM channel`，日志显示失败帧为 9 字节的激活帧，即连接后首帧写入失败。已按此加固：平台侧写入失败时清空句柄并关闭可疑 socket（下次写入重连），入站丢弃改为尽力而为、读取失败不再算写入失败；单帧最多三次尝试，重试前关闭通道、等待 150 ms 再重连，不重发已成功的帧、不回退 BLE。诊断标签 `adb shell setprop log.tag.SunRiseRfcomm DEBUG` 记录 `connect`、`tx` 与 `tx-fail`（含完整原因链，不含完整 payload）。
- 验证：`kotlin.bat test -m shared -p jvm` 82 项全部通过，含「两次失败后第三次重连成功，帧序不变」与「三次尝试用尽后失败且不回退 BLE」两个用例；`kotlin.bat build -m shared` 与 `build -m jvm-app -p jvm` 成功。仍待验证：加固后的实机重试成功率、首帧偶发失败的真实平台原因（看 `tx-fail` 原因链）、长时连续编辑的表现，以及写入后设备是否不再进入单侧无声状态。

## EQ 状态文案简化

- 成功状态显示“已发送” / `Sent`，前置增益显示“已发送前置增益” / `Sent pregain`；删除发送后曲线说明段落及正常操作中的不自动回读提示。页底保留 48 kHz 模型说明，平直确认框保留非恢复出厂/非 Flash 保存的范围说明。
- 仅修改展示文案：内部 `SENT`、`lastSent`、`confirmed`、默认不自动读回、手动提交、重新读取及 Q 操作保持不变。上面的历史验收记录保留当时的旧标题。
- 零蓝牙 JVM 实际界面已检查中英文成功标题、前置增益、页底说明和实时平直确认框，并观察 390 dp、800 dp 窗口截图。单次提交后读取计数保持 1、写入计数为 1，内部 `confirmed=false`；平直确认均取消，没有追加写入。临时 fixture 已删除、桌面入口恢复后，`kotlin.bat build -m jvm-app -p jvm` 成功。本轮未安装 Android 应用或执行实体耳机、双指验收，也未新增字符串断言测试。


## 离线目录与同图频响验收

- 共享 JVM 回归 189 项全部通过；`kotlin.bat build -m shared`、`build -m jvm-app -p jvm`、`build -m android-app` 成功。
- JVM 实际界面验证了默认筛选的零匹配列表、“显示全部”、手动 UUID 参考绑定及恢复自动匹配；切换筛选未断开当前 READY 设备。离线宿主场景的网络调用为 0，同图草稿交互场景为读取 1、配置写入 0。
- 参考/预测同图、独立双轴、390 dp/800 dp 布局及极端有限 SPL 的有界刻度/不可表示提示已检查；不把无法解析的数据画成平直参考。
- 用户已于 2026-10-05 反馈验收完成。实施者未安装或运行 Android，也未向真实耳机提交 EQ；临时烟测入口和 fixture 已移除，桌面入口保留正式 `DesktopApp(window)`。

## 可读快照格式切换

- 快照升级为版本 2：目录直接嵌套 JSON，频响保存为带编码的原文行数组，删除 Base64 编解码路径；内置数据、手动拉取生成器和导入导出同步切换。
- 可读格式切换时的内置快照为 1,555,495 字节，保留 51 条记录、45 个规范化名称和 30 份原始频响，其中 29 份可解析。30 份频响均逐字节还原且原 SHA-256 不变；11 份非 UTF-8 原文使用可逆字节映射，未猜测字符集或替换乱码字节。
- 共享 JVM 回归 190 项全部通过；实际仓库烟测验证离线加载、导入、导出、原子落盘及重载，网络调用为 0。用原有真实 API 资产作为输入的显式拉取和维护脚本烟测通过，未访问在线 CDN；Kotlin 导出通过独立 Python 验证，损坏频响哈希和版本 1 导入均被拒绝且保留当前文件。
- `kotlin.bat build -m shared`、`build -m android-app` 及移除烟测入口后的 `build -m jvm-app -p jvm` 成功；未安装或运行 Android，未与真实耳机通信。临时烟测入口及数据目录已移除。

## 全类型产品目录

- 应用拉取与维护脚本均改为无参数 `/api/v1/products/all`；保留所有非空字符串产品类型，仅在蓝牙设备筛选和按设备名称自动选择参考时要求 `type == "BT"`。离线浏览、手动参考与同图比较不限制类型。
- 2026-10-05 通过中国 CDN 实际拉取全部 51 份频响，内置快照为 2,728,432 字节：107 条记录（BT 51、USB 34、WIRED 22）、101 个规范化名称、50 份可解析曲线。新增 21 份资产，原有 30 份频响字节及 SHA-256 均未改变。
- 共享 JVM 回归 193 项全部通过，覆盖非 BT 同名记录不成为蓝牙候选、不影响自动参考选择、手动参考保留全部类型，以及非 BT 频响也必须完整下载和验证。实际仓库烟测验证离线加载、导入、导出、落盘及重载，网络调用为 0；导出文件通过独立 Python 验证。
- 原生 JVM 界面已验证 107 条目录及类型显示、USB `MOONDROP GM01 Pro` 详情和手动均衡器参考，以及与 WIRED `MOONDROP Variations` 的同图曲线比较。`kotlin.bat build -m shared` 和 `build -m android-app` 成功；未安装或运行 Android，未与真实耳机通信。
- 界面关闭后网络调用数仍为 0；临时烟测入口及数据目录已移除，正式 `kotlin.bat build -m jvm-app -p jvm` 成功。当时的版本 2 实现仍优先读取有效 BT-only 本地快照；这是历史行为，当前版本 3 已拒绝旧格式并回退新内置数据。

## Catalog 单例与双来源频响目录

- 快照统一为版本 3 `sunrise-moondrop-catalog`，内置资源为 `moondrop-catalog.snapshot.json`。同时保存完整产品目录和 `responselib/allwithtag` 原始 JSON；频响库投影为 `Response` 产品，保留 file/tags/未知字段，不把它们视为蓝牙设备或写入 EQ 的许可。
- 实际内置基线包含 107 条物理产品及 48 条 Response，共 155 条记录、99 份原文、98 份可解析曲线；原产品目录和已有 51 份原文/hash 均保留。共享 Kotlin 导出已通过独立 Python 校验和原文逐字节核对。
- 封闭后端前，共享 JVM 回归 206 项通过；实际离线单例烟测验证重复初始化不覆盖导入、关闭后查询/导出保留最后快照、重新初始化从 LOCAL 重载及订阅继续收到新版本，网络调用为 0。
- 最终封闭旧仓库后，全部原仓库行为测试已迁入 `CatalogTest`，设备过滤集成测试改用 Catalog 入口，偏好测试随 `UiSettingsStore` 归位。`kotlin.bat build -m shared -m jvm-app -p jvm` 成功，包含迁移后的 JVM 测试源码编译；保留原有 EQ 页面的非空判断/安全调用警告。
- 按用户要求，最终收口只做编译和静态核对，没有重跑测试、烟测或启动窗口；双窗口交互和完整联网烟测未完成最终验收。临时烟测源码已移除，未构建/运行 Android，未向耳机写入。


## EQ 目标参考频响

- 新增独立的可选目标线、本地选择器、显示/隐藏和清除；源/预测/目标共右轴，保留 EQ 左轴、节点、Q 操作及发送逻辑。
- 本次相关回归：`kotlin.bat test -m shared --include-classes=*CatalogEqResponseTest --include-classes=*UiSettingsStoreTest`，JVM 25 项、Android 宿主 22 项全部通过，覆盖目标极值、独立覆盖范围、隐藏/移除后的轴范围、极端有限值及选择持久化/清除。
- 原生 JVM 界面检查了实际 `Space Travel 2 Ultra` 源与 `5128 Harman In-Ear 2024 Beta` 目标选择、三条频响叠加、目标独立显示、隐藏/清除及偏好重载；零蓝牙编辑 fixture 的节点拖动和滚轮 Q 修改更新草稿/预测，控制读取计数 1、配置写入计数 0。390 dp 中英文与 900 dp 英文布局、缺失目标 UUID、源/目标不覆盖 500 Hz 的提示已检查。临时烟测入口已移除；未安装/运行 Android 或向真实耳机发送。
- 当时完整回归未全绿：`CatalogTest.closeDuringLocalReadCannotPublishALateBundledSnapshot` 在 JVM/Android 宿主均失败；Android 宿主的 `CatalogSnapshotCodecTest.bundledSnapshotActuallyDecodesWithEveryReferencedAssetPresent` 因 `android.util.Log.d` 未模拟失败。该轮未修改这些测试，后续修复与完整回归结果见下。
- `ParamEqScreen()` 清理已由外层非空分支保证的冗余安全调用及重复 `editor != null` 条件；保留 `firstOrNull()?.let` 的空结果处理。`kotlin.bat build -m shared` 的 JVM/Android 编译均成功，未再输出这六条警告；不改变编辑或发送行为。
- `AcousticOverlay` 改为普通类，数组数据沿用对象身份语义，不生成不合适的 data-class 相等/哈希成员；`ParamEqCurve()` 的 `latestEnabled` 改为显式 `rememberUpdatedState` 状态对象，各事件处理器读取 `.value`，保留手势期间动态禁用检查。IDE 复检确认三个数组属性警告和两处恒假条件警告均已消除。
- 后续按用户要求删除 `shared/test/catalog/CatalogEqResponseTest.kt`（22 个测试），停止该文件的 IDE 注解修复；其他测试及正式代码不变。上述通过数量为删除前的历史验证记录，不代表当前仍保留这些用例。
- 后续修复其他测试的 IDE 注解解析：在 `shared/module.yaml` 显式添加 `org.jetbrains.kotlin:kotlin-test:2.4.20:all: compile-only`，提供通用源集的测试元数据；平台 JUnit 4 适配器仍由 Toolchain 隐式提供。`ParamEqValuesTest` 使用通用 `kotlin.test.Test`，不引用平台 `org.junit.Test`。IDE MCP 检查全部 21 个现存测试/fixture 文件，错误为空。
- 关闭测试的加载 fixture 在 `NonCancellable` 中等待释放，模拟关闭期间迟到的读取；保留关闭等待、无快照发布、加载标志清理、无存储写入/网络访问及 fetcher 关闭断言，移除对取消异常具体抛出位置的依赖。正式生命周期实现未修改。
- 将随包资源验收移至 JVM 的 `BundledCatalogSnapshotTest`，继续使用真实 Compose 资源读取器并校验全部引用资产与哈希；不在 Android 宿主单测中调用 Android 资源 API，也不模拟日志或资源。实读结果为 107 条物理产品、48 条 Response、155 条总记录、99 份资产及 98 份可解析曲线。
- 修复后运行完整 `kotlin.bat test -m shared`：JVM 189 项、Android 宿主 171 项全部通过，均为零失败、零跳过；未恢复已按用户要求删除的 22 个 EQ 响应测试。未运行 Android 应用或向耳机发送 EQ。

## 独立 drop 库与 Koin 迁移验收

- 临时 smoke / GATT fixture / PreferencesFactory 源码移除后，运行 `kotlin.bat test -m drop`、`kotlin.bat test -m shared` 和 `kotlin.bat build`：drop 的 JVM/Android 宿主各 30 项，shared 的 JVM 177 项、Android 宿主 159 项全部通过，零失败、零跳过；Android 应用与 JVM 应用构建成功。`kotlin.bat show modules` 仅列出 android-app、drop、jvm-app、shared。批准计划完整保存在 [`.ai/plans/koin-migration-plan.md`](.ai/plans/koin-migration-plan.md)。
- 只复制独立 drop 的 JVM consumer 使用公开参数/数学 API，中心响应 `5.999999999999984 dB`、headroom raw `-366`；非法 Q=0 拒绝，JVM 蓝牙工厂明确 unavailable。Koin 缺依赖 probe 在同版本编译器/插件下给出 `KOIN-D001`，补齐后真实 resolve 成功；fresh Module getter 同样通过负/正验证。Toolchain 富文本诊断渲染器的限制见上文，未关闭 compileSafety。
- 生产 applicationModule / assembleRuntime 双容器烟测：初始化 155 项目录，网络请求与写盘均为 0；真实 HeadsetClient 通过公开字节 GATT fixture 读回五段配置。展示会话重建、重复权限及 predicate 更新后仍为一次 connect、原 session；EQ 配置写入为 0。关闭 A 时唯一 owner 执行 client→manager，旧控件失效；B 仍可读，A 目录 reinit 不污染 B。
- 实际生产桌面入口使用隔离 home / Preferences 文件，观察蓝牙不可用、未知电量、离线目录与源/目标频响选择；隐藏目标保留 UUID，页面切换及目录导入保留源/目标。原生导入/导出取消无副作用；确认导入、落盘和实际导出逐字节一致，SHA-256 为 `af41d5413539b92831bd2b8c3cc9ee7ebf0352daea898d498215721096c98593`。重启显示本地快照 `2026-10-06T00:00:00Z` 并恢复目标 UUID，未连接时源参考仍仅为会话预览。两次正常关窗均退出码 0。截图、实际 JSON 与隔离状态保存在 `%TEMP%\sunrise-koin-ui-j6rla665`，正式源码不保留验收 backend。
- 未安装/运行 Android，未覆盖用户真实目录、绑定或签名；Android Activity 重建、SAF 与后台持续运行没有实机证明。GATT fixture 不证明耳机 DSP 应用、Flash 保存或爆音修复；没有向真实耳机提交 EQ。
