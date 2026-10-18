package ink.lipoly.app.sunrise.drop

import ink.lipoly.app.sunrise.drop.gaia.GaiaPacket

/**
 * 面向界面的瞬时通知，不重放、非可靠事务通道；慢订阅者可能错过事件，应读取 [DropState] 恢复。
 * 请求匹配直接使用绑定内部 pending 槽，不依赖此流。
 */
sealed interface DropEvent {
    /**
     * 9ECA 非响应帧，部分命令同时更新功能状态。
     * @property commandId 原始通知命令编号。
     * @property payload 已解帧的负载，不包含六字节帧头。
     */
    data class SourceNotification(val commandId: Int, val payload: ByteArray) : DropEvent {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SourceNotification) return false
            return commandId == other.commandId && payload === other.payload
        }

        override fun hashCode(): Int = 31 * commandId + payload.contentHashCode()
    }
    /**
     * GAIA type=NOTIFICATION 的帧。
     * @property packet 已解码的厂商、feature、command 与负载。
     */
    data class GaiaNotification(val packet: GaiaPacket) : DropEvent
    /**
     * 当前绑定初始化失败；并非每个控件调用异常都会发布事件。
     * @property cause 归一化后的协议/传输异常。
     */
    data class Error(val cause: DropException) : DropEvent
}
