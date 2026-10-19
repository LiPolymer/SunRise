package ink.lipoly.app.sunrise.headset

import ink.lipoly.app.sunrise.drop.DropEvent

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
