package ink.lipoly.app.sunrise.headset

import ink.lipoly.app.sunrise.bluetooth.BtDevice
import ink.lipoly.app.sunrise.drop.DropOptions
import ink.lipoly.app.sunrise.drop.IBudsController
import ink.lipoly.app.sunrise.drop.moondrop.openMoondrop

/**
 * 一次选择使用固定控制路由；[open] 取得并拥有自己打开的传输。
 *
 * @property searchBleEndpoints 是否执行应用的 BLE 缓存/同名/扫描候选搜索。
 * @property rememberAssociation 采纳成功后是否保存音频地址到端点地址的关联。
 * @property open 以通讯端点和配置身份打开控制器；失败时自行释放已打开资源。
 */
internal class HeadsetRoute(
    val searchBleEndpoints: Boolean,
    val rememberAssociation: Boolean,
    val open: suspend (endpoint: BtDevice, profileDevice: BtDevice) -> IBudsController,
)

/** 为所选音频身份选择控制路由；必须是无副作用、非挂起的判断。 */
internal fun interface HeadsetRouter {
    fun resolve(device: HeadsetDevice): HeadsetRoute
}

/** 现有生产默认：BLE 候选搜索、Moondrop 打开入口与关联持久化。 */
internal fun moondropRouter(options: DropOptions): HeadsetRouter = HeadsetRouter {
    HeadsetRoute(
        searchBleEndpoints = true,
        rememberAssociation = true,
    ) { endpoint, profileDevice ->
        openMoondrop(endpoint, options, profileDevice = profileDevice)
    }
}
