package ink.lipoly.app.sunrise.headset

import android.content.Context
import ink.lipoly.app.sunrise.blueConnector.BtManager
import ink.lipoly.app.sunrise.drop.DropOptions

/**
 * 创建 Android 应用自有的单耳机客户端，复用外部 [bt]，不创建第二个管理器或自动连接。
 *
 * 使用 [Context.getApplicationContext] 的私有 SharedPreferences `drop_verified_devices`，沿用已有数据：
 * 大写音频地址为 key、大写 GATT 端点地址为 value。关联仅在控制器 READY 且会话仍有效后写入；
 * `apply()` 立即更新内存并异步落盘，不保证工厂/连接返回时磁盘写入完成。
 * 历史缓存只是优先尝试线索，既不验证当前连接，也不证明两地址属于同一物理耳机。
 *
 * 创建不会申请权限、配对或建立系统音频连接；宿主应在授权后显式启动客户端。
 * 宿主释放时先关闭客户端，再关闭自己拥有的管理器；客户端 [HeadsetClient.close] 不等待 GATT 清理。
 *
 * @param context Android 上下文，仅取其 applicationContext 保存关联，不持有 Activity。
 * @param bt 外部拥有且仍可用的管理器；本客户端仅管理自身选择/尝试的端点。
 * @param options 传给每次端点控制器的配置，匹配身份仍是所选音频设备而非另址 BLE 端点。
 * @return 尚未开始发现/连接的客户端；common 构造器为 internal，Android 调用方使用本工厂。
 */
fun createHeadsetClient(context: Context, bt: BtManager, options: DropOptions = DropOptions()): HeadsetClient {
    val preferences = context.applicationContext.getSharedPreferences("drop_verified_devices", Context.MODE_PRIVATE)
    val associations = object : HeadsetAssociations {
        override fun endpoint(address: String): String? = preferences.getString(address.uppercase(), null)
        override fun remember(address: String, endpoint: String) {
            preferences.edit().putString(address.uppercase(), endpoint.uppercase()).apply()
        }
    }
    return HeadsetClient(bt, associations, options)
}
