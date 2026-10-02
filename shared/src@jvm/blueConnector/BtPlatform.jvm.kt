package ink.lipoly.app.sunrise.blueConnector

/** JVM 宿主标记；可由调用方继承，但不意味着存在桌面蓝牙后端。 */
actual abstract class BtHost
/**
 * JVM 桌面明确不提供蓝牙，不创建假设备、模拟连接或空管理器。
 * @param host JVM 宿主标记；不用于创建任何资源。
 * @throws UnsupportedOperationException 始终抛出 `Bluetooth is unavailable on JVM desktop`。
 */
actual fun createBtManager(host: BtHost): BtManager =
    throw UnsupportedOperationException("Bluetooth is unavailable on JVM desktop")
