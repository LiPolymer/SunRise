package ink.lipoly.app.sunrise.headset

import ink.lipoly.app.sunrise.blueConnector.BtDevice
import ink.lipoly.app.sunrise.drop.DropEvent
import ink.lipoly.app.sunrise.drop.DropState

/**
 * 应用级单耳机的选择与连接阶段；协议初始化和功能读值另见 [HeadsetState.controls]。
 *
 * - [IDLE]：尚未启动、无已连接音频候选或已主动断开；不表示客户端已关闭。
 * - [DISCOVERING]：自动模式正在刷新系统的已连接音频设备。
 * - [CONNECTING]：正在为选中设备尝试 GATT 端点，首次失败尚未成功过的尝试仍使用此阶段。
 * - [PROBING]：控制器探测协议/能力，或已经探测完成但端点尚未被应用采纳。
 * - [READY]：端点已采纳且控制器就绪；不保证每项能力存在或所有功能已有读值。
 * - [RECONNECTING]：曾就绪的目标丢失会话，正在清理或重试；此时不可操作功能控件。
 * - [SELECTION_REQUIRED]：自动发现多个原始音频候选，等待调用方选择，不按品牌筛选。
 * - [ERROR]：保留最近的实际异常；自动尝试或曾就绪目标仍可能随后重试。
 */
enum class HeadsetPhase {
    IDLE, DISCOVERING, CONNECTING, PROBING, READY, RECONNECTING, SELECTION_REQUIRED, ERROR
}

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

/**
 * 应用连接身份与协议功能状态的最新快照，晚订阅者应以此恢复界面。
 *
 * 功能按钮必须同时检查 [phase]、[controls] 的控制阶段以及目标协议/能力，不能只看旧功能读值。
 *
 * @property phase 应用级发现、选择和连接阶段。
 * @property device 选中的音频身份快照；无目标或等待多设备选择时为空，不被 BLE 端点身份替换。
 * @property controls 当前控制器的协议、能力和功能读值；清理/切换时恢复默认未知状态。
 * @property error 实际连接或控制异常，保留其类型；为空不等于功能值已经得到确认。
 */
data class HeadsetState(
    val phase: HeadsetPhase = HeadsetPhase.IDLE,
    val device: HeadsetDevice? = null,
    val controls: DropState = DropState(),
    val error: Exception? = null,
)

/**
 * 面向界面的瞬时通知，不能用作可靠事务回复或历史记录。
 *
 * [HeadsetClient.events] 不重放事件，使用有限缓冲的尽力投递；界面恢复以 [HeadsetClient.state] 为准。
 */
sealed interface HeadsetEvent {
    /**
     * 包装当前控制器的协议事件，不更改其异常子类型或载荷。
     * @property event 控制层事件；其生命周期受当前连接与端点身份检查保护。
     */
    data class Control(val event: DropEvent) : HeadsetEvent
    /**
     * 当前连接循环报告的失败，重试是否继续取决于手动首次失败或自动/曾就绪策略。
     * @property cause 原始异常，可为蓝牙异常或控制异常，不统一转换成“不支持”。
     */
    data class Error(val cause: Exception) : HeadsetEvent
}

/**
 * 应用私有的音频地址到 GATT 端点地址关联存储，不参与通用蓝牙设备身份管理。
 *
 * 存储命中只决定优先尝试顺序；仅在协议 READY 且端点仍有效时记住新关联。
 */
internal interface HeadsetAssociations {
    /**
     * 查询先前成功采用的端点；调用方使用大写音频地址，返回值仍须由管理器校验。
     * @param address 所选音频设备地址。
     * @return 已存端点地址，无记录时为空；记录不保证端点仍可连接。
     */
    fun endpoint(address: String): String?
    /**
     * 在端点采纳后保存关联；持久化时机由平台实现决定，不表示物理身份认证。
     * @param address 用于显示和 profile 匹配的音频地址。
     * @param endpoint 本次已完成控制器初始化的 GATT 地址。
     */
    fun remember(address: String, endpoint: String)
}
