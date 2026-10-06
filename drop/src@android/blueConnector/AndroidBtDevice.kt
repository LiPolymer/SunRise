package ink.lipoly.app.sunrise.blueConnector

import android.bluetooth.BluetoothDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 管理器缓存的规范地址身份，初始信息未知，实际观测再更新。
 *
 * 每个实例只创建一个 [AndroidBtGatt]；同名不同地址不合并，句柄不自行拥有全局发现策略。
 * @property owner 拥有缓存、权限检查和后台 scope 的管理器。
 * @property nativeDevice 与 address 对应的原生设备句柄。
 * @property address 已通过管理器验证的大写 MAC 地址。
 */
internal class AndroidBtDevice(
    internal val owner: AndroidBtManager,
    internal val nativeDevice: BluetoothDevice,
    override val address: String,
) : BtDevice {
    override val manager: BtManager get() = owner
    private val mutableInfo = MutableStateFlow(BtDeviceInfo())
    override val info = mutableInfo.asStateFlow()
    override val gatt = AndroidBtGatt(this)
    /** LE 地址上该句柄通常找不到 SPP 记录；能否连接由打开结果决定，不在此处推断设备类型。 */
    override val rfcomm: BtRfcomm = AndroidBtRfcomm(owner, nativeDevice, address)

    /** 仅在事实有变化时替换状态，返回是否变化；首次发现事件由管理器的 observed 集合决定。 */
    internal fun updateInfo(value: BtDeviceInfo): Boolean {
        if (mutableInfo.value == value) return false
        mutableInfo.value = value
        return true
    }

    /** 调用公开 createBond；Boolean 仅表示平台接受请求，配对结果等待系统广播更新 [info]。 */
    override fun requestBond(): Boolean {
        owner.enforceBluetoothPermission()
        return owner.platformOperation("request bond") {
            try {
                nativeDevice.createBond()
            } catch (error: SecurityException) {
                throw owner.permissionFailure("request bond", cause = error)
            }
        }
    }
}
