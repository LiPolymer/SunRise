package ink.lipoly.app.sunrise.drop

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 一次传输会话一个实例；换设备或重连时由调用方关闭旧实例并重新创建。
 * [close] 幂等，释放本实例打开的传输；关闭后所有旧能力调用抛 [DropException.Disconnected]，
 * [state] 恢复默认 `DropState()`。控制器不负责发现、选择或自动重连。
 */
interface IBudsController {
    /** 本会话的功能快照；关闭后恢复默认值。 */
    val state: StateFlow<DropState>
    /** 无重放、best-effort 的瞬时通知；不用于事务匹配。 */
    val events: SharedFlow<DropEvent>
    /** 幂等释放本实例拥有的传输及协议任务。 */
    suspend fun close()
}

/**
 * 电量能力由控制器实现额外实现，调用方用 `as? BatteryControls` 取得。
 * 没有该能力时 cast 为 null，不提供返回构造值的空实现。
 */
interface BatteryControls {
    /** 读取并合并电量组件，同时更新 [DropState.battery]；旧引用抛 [DropException.Disconnected]。 */
    suspend fun readBattery(): EarbudBatteryReading
}

/**
 * 降噪能力由控制器实现额外实现，调用方用 `as? NoiseControls` 取得。
 * 没有该能力时 cast 为 null，不提供返回构造值的空实现。
 * 写入返回读回确认值，不承诺持久化；无法确认或失配分别抛 [DropException.Unverified]
 * 或 [DropException.AncModeMismatch]，旧引用抛 [DropException.Disconnected]。
 */
interface NoiseControls {
    /** 读取当前模式；设备返回未映射编号时抛 [DropException.Protocol]。 */
    suspend fun readNoise(): AncMode
    /** 写入 [mode] 并返回读回值；不支持的模式抛 [DropException.UnsupportedCapability]。 */
    suspend fun writeNoise(mode: AncMode): AncMode
}
