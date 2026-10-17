package ink.lipoly.app.sunrise.bluetooth

/** JVM 宿主标记；桌面工厂仅创建进程内模拟设备，不访问系统蓝牙。 */
actual abstract class BtHost
/**
 * 创建独立的 JVM 模拟管理器，供桌面使用真实协议控制器查看界面。
 * @param host JVM 宿主标记；不用于创建系统资源。
 * @return 调用方拥有的模拟管理器，最终必须调用 [BtManager.close]。
 */
actual fun createBtManager(host: BtHost): BtManager = JvmMockBtManager()
