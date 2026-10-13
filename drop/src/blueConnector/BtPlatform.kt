package ink.lipoly.app.sunrise.blueConnector

/**
 * 创建蓝牙管理器所需的平台宿主。
 *
 * Android actual 为 `android.content.Context`，工厂只保留其 applicationContext；
 * JVM actual 是抽象标记类，工厂创建用于桌面 UI 检查的进程内模拟设备，不访问真实蓝牙。
 */
expect abstract class BtHost
/**
 * 创建由调用方拥有的 [BtManager]，不申请权限、扫描、配对或自动连接。
 *
 * @param host Android 上下文（使用应用上下文而非 Activity 生命周期）或 JVM 宿主标记。
 * @return Android 真实管理器或 JVM 模拟管理器；调用方最终必须调用 [BtManager.close]。
 * @throws BtException.Transport Android 注册系统广播接收器失败。
 */
expect fun createBtManager(host: BtHost): BtManager
