package ink.lipoly.app.sunrise.bluetooth

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 管理器可观察到的蓝牙可用性，不代表权限已授予或任意设备已连接。
 *
 * `UNKNOWN` 表示暂时无法读取无线状态（Android 缺 CONNECT 权限时通常如此）；
 * `UNAVAILABLE` 表示无适配器；`DISABLED` 表示无线关闭；`ENABLED` 表示可读取到开启状态；
 * `CLOSED` 是 [BtManager.close] 后的终态。具体操作仍会重新核对权限和无线状态。
 */
enum class BtAvailability { UNKNOWN, UNAVAILABLE, DISABLED, ENABLED, CLOSED }
/**
 * 协议无关的蓝牙资源所有者，缓存每个地址的稳定 [BtDevice] 并管理其独立 GATT 生命周期。
 *
 * 创建管理器不申请权限、不扫描、不配对、不连接。Android 按规范大写 MAC 缓存，
 * 不按名称合并设备；[close] 释放所有所属连接。调用方负责持有并最终关闭管理器。
 * [events] 是瞬时信息，恢复界面应读取各 [StateFlow] 快照而非等待历史事件。
 */
interface BtManager {
    /** 最近可观察的适配器状态；权限变化后可由后续操作或系统广播刷新。 */
    val availability: StateFlow<BtAvailability>
    /** 已获取的地址句柄快照，Android 按地址排序；不表示这些设备都已被发现或连接。 */
    val devices: StateFlow<List<BtDevice>>
    /** 最近 A2DP/HEADSET 枚举结果；经典音频连接不证明存在可用的 GATT 服务。 */
    val connectedAudioDevices: StateFlow<List<BtDevice>>
    /** 无历史重放的发现、信息、无线和 GATT 事件；关闭时不保证最终事件送达。 */
    val events: SharedFlow<BtEvent>
    /**
     * 获取或复用地址句柄，不执行发现、扫描或连接，也不发出 [BtEvent.OnDiscovered]。
     *
     * @param address Android 接受的 MAC 地址；小写规范为大写，不自动裁剪空白。
     * @return 本管理器内该地址的稳定句柄，初始 [BtDevice.info] 可以未知。
     * @throws BtException.InvalidDevice 地址格式无效。
     * @throws BtException.BluetoothUnavailable Android 无蓝牙适配器。
     * @throws BtException.Disconnected 管理器已关闭。
     * @throws BtException 平台获取句柄失败，包括权限或传输错误。
     */
    fun device(address: String): BtDevice
    /**
     * 显式枚举当前 A2DP 和 HEADSET 音频连接，更新 [connectedAudioDevices] 并观测设备信息。
     *
     * Android 按地址去重排序；每个 profile 获取代理最多等 1,500 毫秒，代理不可得或超时
     * 可贡献空列表，因此返回值是尽力枚举而非系统音频连接的强一致保证。
     * @return 不筛品牌或应用协议的音频设备列表。
     * @throws BtException 权限不足、无线不可用、资源已关闭或平台操作失败。
     */
    suspend fun refreshConnectedAudioDevices(): List<BtDevice>
    /**
     * 枚举系统已配对设备并更新设备观测；不发起配对或推断音频地址与 LE 地址的关联。
     * @return Android 按地址排序的稳定句柄列表。
     * @throws BtException 权限不足、无线不可用、资源已关闭或枚举失败。
     */
    suspend fun bondedDevices(): List<BtDevice>
    /**
     * 扫描 LE 设备；同一 Android 管理器的扫描串行，其他设备的 GATT 操作不占扫描锁。
     *
     * 两个过滤参数均为空时接受全部结果；否则名称或地址任一匹配即接受，忽略大小写，
     * 命中指定地址提前结束。超时正常返回已找到的去重列表，取消透传并尽力停止扫描。
     * Android scanner 不可得时返回空列表，扫描失败回调或扫描缓冲溢出则抛传输错误。
     * @param name 可选的精确名称，不是子串或品牌过滤。
     * @param address 可选 MAC 地址，先验证并规范化。
     * @param timeoutMillis 开始扫描后的期限，单位毫秒，必须大于零；不含等待扫描锁的时间。
     * @return Android 按地址排序的匹配设备列表；空列表不证明设备不存在。
     * @throws IllegalArgumentException 期限非正数。
     * @throws BtException.InvalidDevice 地址格式无效。
     * @throws BtException 权限不足、无线不可用、关闭或扫描失败。
     */
    suspend fun scanLe(name: String? = null, address: String? = null, timeoutMillis: Long = 8_000): List<BtDevice>
    /**
     * 幂等关闭管理器：停止自有任务、注销广播、失败化发现请求并使全部所属 GATT 失效。
     * 可用性进入 [BtAvailability.CLOSED]；已缓存句柄仍可被持有，但不能继续无线操作。
     * 不执行系统取消配对或关闭整个系统蓝牙适配器。
     */
    fun close()
}
/** 管理器级瞬时事件；不重放，发送者明确，不含品牌/协议识别或音频到 LE 的推断。 */
sealed interface BtEvent {
    /**
     * 某地址首次被平台枚举、扫描或广播实际观测到；不表示已建立 GATT。
     * @property device 本管理器内稳定设备句柄。
     * @property sender 观测该设备的管理器。
     */
    data class OnDiscovered(val device: BtDevice, val sender: BtManager) : BtEvent
    /**
     * 已观测设备的信息变化；重复且信息相同的观测不产生此事件。
     * @property device 已更新 [BtDevice.info] 的句柄。
     * @property sender 拥有该设备的管理器。
     */
    data class OnDeviceChanged(val device: BtDevice, val sender: BtManager) : BtEvent
    /**
     * 适配器可观察状态变化；最终关闭事件是尽力发布，不代替状态快照。
     * @property availability 新的可用性。
     * @property sender 状态所属管理器。
     */
    data class OnBluetoothStateChanged(val availability: BtAvailability, val sender: BtManager) : BtEvent
    /**
     * 单设备 GATT 状态变化，等价事实以 [GattController.state] 为准。
     * @property device 发生变化的设备。
     * @property state 发布时的快照。
     * @property sender 设备所属管理器。
     */
    data class OnGattStateChanged(val device: BtDevice, val state: GattState, val sender: BtManager) : BtEvent
    /**
     * 异步广播处理错误或 GATT 状态携带的失败；并非所有直接调用抛出的错误都会广播。
     * @property cause 真实蓝牙层错误，不改写为上层协议错误。
     * @property sender 错误所属管理器。
     * @property device 有明确关联时的设备，否则为空。
     */
    data class OnError(val cause: BtException, val sender: BtManager, val device: BtDevice? = null) : BtEvent
}
/**
 * 通用蓝牙错误族；参数范围错误使用 [IllegalArgumentException]，协程调用者取消原样传播。
 * @param message 可读诊断信息，不作为机器判别码。
 * @param cause 平台原始原因（可有可无）；优先按子类型处理，而非匹配 message。
 */
sealed class BtException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** 系统没有适配器或蓝牙未开启，不等于目标不支持某种应用协议。 */
    class BluetoothUnavailable : BtException("Bluetooth is unavailable or disabled")
    /**
     * Android 操作缺少运行时权限；本层不会弹出授权界面。
     * @property permissions 完整权限名集合，例如 `android.permission.BLUETOOTH_CONNECT`。
     */
    class MissingPermission(val permissions: Set<String>) : BtException("Missing Bluetooth permissions: ${permissions.joinToString()}")
    /**
     * 设备地址格式不合法，不表示暂时扫描不到。
     * @param message 具体地址格式诊断。
     */
    class InvalidDevice(message: String) : BtException(message)
    /** 会话/连接尝试已断开、被主动关闭，或所属管理器已关闭。 */
    class Disconnected : BtException("Bluetooth session is disconnected")
    /**
     * 内部无线期限到达，不表示调用者主动取消；原生 GATT 操作超时会终止该会话。
     * @property operation 超时操作的诊断名称，不作为固定枚举码。
     */
    class Timeout(val operation: String) : BtException("Bluetooth operation timed out: $operation")
    /**
     * 特征属性或 CCCD 不支持请求的通用 GATT 操作，不表示整个设备不能连接。
     * @property operation 未支持的操作诊断名称。
     */
    class UnsupportedOperation(val operation: String) : BtException("Unsupported Bluetooth operation: $operation")
    /** 提交了非实现生成的特征，或特征不属于当前会话；即使 UUID 相同也不合法。 */
    class InvalidGattHandle : BtException("GATT characteristic belongs to another session")
    /** 单次写入负载超过协商 MTU 减 3；本层不自动分片。 */
    class PacketTooLarge : BtException("Packet exceeds negotiated GATT MTU")
    /**
     * 平台拒绝、失败回调、缓冲溢出等传输错误。
     * @param message 带操作上下文的诊断信息。
     * @param cause 可选平台原始异常；非成功状态回调可能只有 message。
     */
    class Transport(message: String, cause: Throwable? = null) : BtException(message, cause)
}
