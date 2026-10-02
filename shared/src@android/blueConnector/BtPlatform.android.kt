package ink.lipoly.app.sunrise.blueConnector

import android.content.Context

/** Android 宿主是公开 [Context]；[createBtManager] 不持有 Activity 专属上下文。 */
actual typealias BtHost = Context

/**
 * 用 [Context.getApplicationContext] 创建真实 Android 管理器，不隐式授权或连接。
 * @param host 任意可取得 applicationContext 的 Android 上下文。
 * @return 调用方拥有、最终需 [BtManager.close] 的管理器。
 * @throws BtException.Transport 系统广播接收器注册失败。
 */
actual fun createBtManager(host: BtHost): BtManager = AndroidBtManager(host.applicationContext)
