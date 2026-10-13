# SunRise 

> [!caution]
> <img src="https://lipoly.ink/assets/badges/llmWarn.svg" alt="This project contains unaudited AI generated code" width="200">
> 
> under construction

一个基于 Compose Multiplatform 构建的 Moondrop APP 替代；Android 使用真实蓝牙，JVM 使用模拟耳机预览界面。

目前仅支持蓝牙设备, 有对 USB 设备的支持打算

桌面预览运行 `kotlin.bat run -m jvm-app`。窗口标题标注“模拟设备”，启动后自动连接内存中的 `MOONDROP MOCA`，可检查电量、降噪、增益、编码、LED、空间音频、头部追踪和五段 PEQ 的交互。设备参数在同一运行时内断连重连后保留，退出后重置；不会访问真实蓝牙或向实体耳机发送命令。仅模拟 GAIA，不提供 9ECA、RFCOMM、音频播放或 DSP/Flash 验证；目录和目录偏好仍沿用桌面持久化。