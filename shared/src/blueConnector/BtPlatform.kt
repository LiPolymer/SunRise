package ink.lipoly.app.sunrise.blueConnector

/**
 * 创建蓝牙管理器所需的平台宿主。
 *
 * Android actual 为 `android.content.Context`，工厂只保留其 applicationContext；
 * JVM actual 是抽象标记类，没有可用的桌面蓝牙后端。
 */
expect abstract class BtHost
/**
 * 创建由调用方拥有的 [BtManager]，不申请权限、扫描、配对或自动连接。
 *
 * @param host Android 上下文；工厂使用应用上下文而非 Activity 生命周期。
 * @return Android 管理器；调用方最终必须调用 [BtManager.close]。
 * @throws BtException.Transport Android 注册系统广播接收器失败。
 * @throws UnsupportedOperationException JVM 桌面无蓝牙实现，始终抛出而非返回模拟管理器。
 */
expect fun createBtManager(host: BtHost): BtManager
