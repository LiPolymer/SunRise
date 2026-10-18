package ink.lipoly.app.sunrise.drop

/**
 * 控制层错误；参数范围检查另抛 [IllegalArgumentException]，调用方协程取消原样传播。
 * 传输类型映射见 [DropException.Transport]；关闭控制器不意味着关闭底层 GATT。
 * @param message 可供宿主展示/诊断的错误说明，不作为稳定机器错误码。
 * @param cause 原始错误；仅显式包装路径保留，不承诺所有类型映射都保留 cause。
 */
sealed class DropException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** 已连接会话没有 GAIA 或 9ECA 命令/响应特征对；普通 GATT 连接仍由调用方拥有。 */
    class UnsupportedDevice : DropException("Device does not expose GAIA or 9ECA")
    /**
     * 缺少协议、功能或可读特征，亦用于原生 UnsupportedOperation/InvalidGattHandle。
     * @property capability 不支持的功能说明。
     */
    class UnsupportedCapability(val capability: String) : DropException("Unsupported capability: $capability")
    /** 尚无已连接会话、未完成初始化，或控件访问时当前绑定未 READY。 */
    class NotReady : DropException("Earbud protocol is not ready")
    /**
     * 协议回复等待、音源稳定等待或底层原生操作超时；只有底层原生超时可能使 GATT 失效。
     * @property operation 超时操作说明。
     */
    class Timeout(val operation: String) : DropException("Timed out: $operation")
    /** 绑定已关闭、换会话、失去连接或控制器已关闭；旧控件引用也抛此异常。 */
    class Disconnected : DropException("Earbud disconnected")
    /**
     * ANC/codec 已写入但后续 GET 无法确认；对应快照变未知。
     * @property operation 未能确认的操作。
     * @param cause GET 读回失败的原异常；取消/断连不在此包装。
     */
    class Unverified(val operation: String, cause: Throwable? = null) :
        DropException("$operation command was sent, but readback could not be verified", cause)
    /**
     * ANC 最多四次读回仍与目标不一致；状态保留最后实际观察值。
     * @property requested 请求逻辑模式。
     * @property observed 最后读回逻辑模式。
     */
    class AncModeMismatch(val requested: AncMode, val observed: AncMode) :
        DropException("ANC mode readback mismatch: requested $requested, observed $observed")
    /** Actual device codec option differs from the requested option. */
    class CodecStateMismatch(val codec: AudioCodec, val requested: Boolean, val observed: Boolean) :
        DropException("$codec readback mismatch: requested $requested, observed $observed")
    /**
     * 负载短缺、未知枚举、编码不合法或数据超过协商 GATT MTU 等协议错误。
     * @param message 实际结构/数值错误说明。
     */
    class Protocol(message: String) : DropException(message)
    /**
     * 9ECA 响应状态被拒绝；SET_AUDIO_SOURCE 的 5/9 有专用等待规则。
     * @property status 原始无符号状态码。
     * @param operation 被拒绝的操作说明。
     */
    class Rejected(val status: Int, operation: String) : DropException("$operation rejected with status $status")
    /**
     * 未单独映射的传输/初始化错误，保留原异常。
     * @param message 原始错误消息或控制层缺省说明。
     * @param cause 原始异常（例如权限或蓝牙不可用错误）。
     */
    class Transport(message: String, cause: Throwable? = null) : DropException(message, cause)
}
