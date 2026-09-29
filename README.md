# SunRise

Kotlin Toolchain 0.12.2 项目，包含 Android 应用、JVM 桌面应用和 Android/JVM 共享库。`shared/src/drop` 保留 GAIA / 9ECA 协议及控制逻辑，`shared/src/compose` 是两平台共用的概览、设置和诊断界面；平台实现分别在 `shared/src@android` 与 `shared/src@jvm`。Kotlin 包名仍为 `ink.lipoly.app.sunrise`。

## 运行

Windows 在项目根目录执行：

```bat
kotlin.bat build
kotlin.bat test -m shared
kotlin.bat run -m jvm-app
kotlin.bat run -m android-app
```

Android 构建需要 Android SDK 37；运行需要已连接的设备或模拟器。Android 首次启动请求蓝牙权限；先在系统蓝牙中连接音频耳机，应用会尝试自动连接，多个候选设备时要求选择。概览显示连接及电量，未知值为“—”；ANC 等控制只在能力及读回确认后可用。设置中的主题、语言和界面选项存入 `sunrise_ui` 偏好设置，重启后保留。未连接设备时仍可查看界面，设备控制不可用。

JVM 桌面运行同一套界面，但不提供蓝牙客户端：概览和权限区域明确提示当前平台不可用，刷新及连接操作不可用，诊断不提供可执行蓝牙动作。桌面设置只保存在本次进程内，重启恢复默认。

`DropClient` 由 Android Activity 创建，宿主销毁时调用 `close()`；Android 12+ 需要 `BLUETOOTH_CONNECT`，扫描还需要 `BLUETOOTH_SCAN`，旧版 Android 的 LE 扫描需要定位权限。GAIA 降噪写入以读回状态为准，读回失败会将模式标为未知；9ECA 设备控制仍需真实耳机验证。

编译说明：共享模块用 `-Xexpect-actual-classes` 确认 Kotlin `DropHost` expect/actual 类仍处于 Beta；Android GATT 为兼容 API 24–32 保留旧版回调，只在这两个覆盖方法上抑制弃用警告。构建时若出现 Compose Material3 版本推断提示，它与上述编译器警告无关。
