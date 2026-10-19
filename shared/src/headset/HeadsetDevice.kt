package ink.lipoly.app.sunrise.headset

import ink.lipoly.app.sunrise.bluetooth.BtDevice

/**
 * 音频选择身份的不可变观察快照，而非当前 GATT 端点或协议支持证明。
 *
 * 发现结果不会随 [device] 的信息变化而原地更新；客户端收到相关设备事件时重建当前显示快照。
 *
 * @property device 所选设备的稳定蓝牙句柄；传给客户端连接时应属于同一个管理器。
 * @property name 创建快照时的设备名，可能为空；不证明同名 BLE 端点属于同一物理耳机。
 * @property verified 仅表示该地址存在记住的音频地址到端点地址关联，不代表本次连接或硬件验证成功。
 */
data class HeadsetDevice(val device: BtDevice, val name: String?, val verified: Boolean = false) {
    /** 所选音频设备的地址，来自 [device]；实际控制通讯可使用另一个 BLE 地址。 */
    val address: String get() = device.address
}
