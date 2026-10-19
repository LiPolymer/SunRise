package ink.lipoly.app.sunrise.headset

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
 * - [SELECTION_REQUIRED]：自动发现多个被当前自动策略接受的音频候选，等待调用方选择。
 * - [ERROR]：保留最近的实际异常；自动尝试或曾就绪目标仍可能随后重试。
 */
enum class HeadsetPhase {
    IDLE, DISCOVERING, CONNECTING, PROBING, READY, RECONNECTING, SELECTION_REQUIRED, ERROR
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
