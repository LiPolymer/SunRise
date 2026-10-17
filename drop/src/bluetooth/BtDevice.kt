package ink.lipoly.app.sunrise.bluetooth

import kotlinx.coroutines.flow.StateFlow

/**
 * 平台报告的设备传输类型：`UNKNOWN` 未知，`CLASSIC` 经典蓝牙，`LE` 低功耗，
 * `DUAL` 双模。类型不证明设备实现了某种应用协议，也不关联两个不同地址。
 */
enum class BtDeviceKind { UNKNOWN, CLASSIC, LE, DUAL }
/** 系统配对事实：`NONE` 未配对，`BONDING` 配对进行中，`BONDED` 已配对；不等同于 GATT 连接。 */
enum class BtBondState { NONE, BONDING, BONDED }
/**
 * 最近一次平台观测得到的设备信息；仅获取地址句柄时可以仍为默认未知值。
 *
 * @property name 系统名称，扫描时可回退到广播名称；可能为空。
 * @property kind 系统报告的传输类型。
 * @property bondState 系统报告的配对阶段，而非 [BtDevice.requestBond] 的返回值。
 */
data class BtDeviceInfo(
    val name: String? = null,
    val kind: BtDeviceKind = BtDeviceKind.UNKNOWN,
    val bondState: BtBondState = BtBondState.NONE,
)
/** 管理器拥有的稳定设备身份；同一管理器中的不同设备拥有独立的 [gatt] 和会话队列。 */
interface BtDevice {
    /** 拥有该句柄及其连接资源的管理器；关闭设备会话不会关闭它。 */
    val manager: BtManager
    /** Android 规范大写 MAC 地址；不是名称，也不是应用自行推断的音频关联地址。 */
    val address: String
    /**
     * 该地址的经典 RFCOMM 入口；Android 提供句柄，其他平台为 null。
     *
     * 句柄只表示可用入口，不表示对端是经典设备、已配对或提供任何服务；能否连接由
     * [RfcommStream.open] 判定。LE 地址上的句柄通常找不到 SPP 记录。
     */
    val rfcomm: RfcommStream?
    /** 最近观测的信息状态；获取句柄本身不保证已读取系统名称或配对事实。 */
    val info: StateFlow<BtDeviceInfo>
    /** 该设备唯一的 GATT 生命周期入口，连接和断开均需显式调用。 */
    val gatt: GattController
    /**
     * 向系统请求配对，不等待完成，不使用隐藏解绑或强制音频连接 API。
     * @return 系统是否接受本次请求；`true` 不表示已配对，结果观察 [info] 的配对状态。
     * @throws BtException 权限不足、无线不可用、管理器关闭或平台配对请求失败。
     */
    fun requestBond(): Boolean
}
