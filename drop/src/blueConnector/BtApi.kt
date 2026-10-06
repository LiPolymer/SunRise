package ink.lipoly.app.sunrise.blueConnector

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
/**
 * 经典蓝牙 RFCOMM 字节通道：Android 按 SPP 服务记录解析通道后连接，JVM 桌面不提供实现。
 *
 * 通道只搬运字节，不解释 GAIA 帧、不做请求/响应匹配或流拼帧，帧边界由调用方保证。
 * 打开时自动完成配对校验与 SDP 查询；取消等待不关闭已开始的平台连接，[close] 才释放 socket。
 */
interface BtRfcomm {
    /** 通道远端的规范大写地址，仅用于诊断，不用于会话身份匹配。 */
    val address: String
    /**
     * 打开通道；已打开时直接返回。
     * @throws BtException 权限不足、对端未配对、找不到服务记录或连接失败。
     */
    suspend fun open()
    /**
     * 向已打开的通道写入一段字节并刷新。
     * @throws BtException 尚未打开、已关闭或平台写入失败。
     */
    suspend fun write(bytes: ByteArray)
    /** 幂等关闭通道并释放平台 socket；未打开或已关闭时不做任何事。 */
    suspend fun close()
}
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
     * [BtRfcomm.open] 判定。LE 地址上的句柄通常找不到 SPP 记录。
     */
    val rfcomm: BtRfcomm?
    /** 最近观测的信息状态；获取句柄本身不保证已读取系统名称或配对事实。 */
    val info: StateFlow<BtDeviceInfo>
    /** 该设备唯一的 GATT 生命周期入口，连接和断开均需显式调用。 */
    val gatt: BtGatt
    /**
     * 向系统请求配对，不等待完成，不使用隐藏解绑或强制音频连接 API。
     * @return 系统是否接受本次请求；`true` 不表示已配对，结果观察 [info] 的配对状态。
     * @throws BtException 权限不足、无线不可用、管理器关闭或平台配对请求失败。
     */
    fun requestBond(): Boolean
}
/**
 * 设备 GATT 阶段：`DISCONNECTED` 无当前会话；`CONNECTING` 连接或服务发现中；
 * `CONNECTED` 已完成服务发现（不代表应用协议可用）；`ERROR` 非正常失败；
 * `CLOSED` 管理器已关闭，不可再次连接。主动断开通常回到 `DISCONNECTED`。
 */
enum class GattPhase { DISCONNECTED, CONNECTING, CONNECTED, ERROR, CLOSED }
/**
 * 一个设备的 GATT 生命周期快照；会话身份比 UUID 或地址更适合识别换绑。
 * @property phase 当前阶段。
 * @property session 仅就绪连接携带的会话；连接中和终止状态通常为空。
 * @property error 失败原因；Android 非断连失败进入 `ERROR`，正常断开和关闭不携带错误。
 */
data class GattState(
    val phase: GattPhase = GattPhase.DISCONNECTED,
    val session: GattSession? = null,
    val error: BtException? = null,
)
/** 每设备独立的连接入口；[connect] 的等待取消与 [disconnect] 的资源取消不同。 */
interface BtGatt {
    /** 当前连接事实，晚订阅者通过快照取得会话；不以通知事件推断连接生命周期。 */
    val state: StateFlow<GattState>
    /**
     * 显式连接并等待服务发现完成，不自动订阅特征或识别上层协议。
     *
     * Android 复用当前有效会话或进行中的同一个连接任务；不同设备独立连接。
     * 连接及服务发现各有 12 秒期限。取消一个调用者仅取消其等待，不取消共享无线任务；
     * 要终止资源必须调用 [disconnect]、[GattSession.close] 或 [BtManager.close]。
     * @return 已完成服务发现的当前会话，允许服务列表为空或不含应用协议。
     * @throws BtException 权限、无线、平台连接、发现、超时或关闭失败。
     */
    suspend fun connect(): GattSession
    /**
     * 立即分离当前会话/连接尝试，取消无线连接任务并使操作失败，锁外等待旧连接任务结束。
     * 通常回到 [GattPhase.DISCONNECTED]；管理器关闭后保持 [GattPhase.CLOSED]。
     * 不关闭管理器，不影响其他设备；等待本方法被取消也不会恢复已分离的旧连接。
     */
    suspend fun disconnect()
}
/**
 * 平台特征属性：`READ` 可读，`WRITE` 带响应写，`WRITE_NO_RESPONSE` 无响应写，
 * `NOTIFY` 通知，`INDICATE` 指示。当前 [GattSession.write] 只使用 `WRITE`，
 * 暴露 `WRITE_NO_RESPONSE` 不表示提供了该写入模式。
 */
enum class GattProperty { READ, WRITE, WRITE_NO_RESPONSE, NOTIFY, INDICATE }
/** 服务发现生成的会话专属特征句柄；不可自己构造替代实现或按 UUID 跨会话复用。 */
interface GattCharacteristic {
    /** 所属服务的完整 UUID；Android 为规范小写字符串。 */
    val serviceUuid: String
    /** 特征的完整 UUID；Android 为规范小写字符串，同 UUID 不代表同一个原生句柄。 */
    val uuid: String
    /** 从平台特征位映射的能力集合；实际操作仍可能被设备或系统拒绝。 */
    val properties: Set<GattProperty>
}
/**
 * 一次服务发现的服务快照，不携带协议含义。
 * @property uuid 完整服务 UUID，Android 为规范小写字符串。
 * @property characteristics 本会话发现的特征句柄列表；只能向该会话提交。
 */
data class GattService(val uuid: String, val characteristics: List<GattCharacteristic>)
/**
 * 一次 GATT 连接及其服务、特征、操作队列的资源边界。
 *
 * Android 每会话以容量 64 的队列串行化原生操作；调用方取消未开始的请求会跳过，
 * 已开始的请求继续排空原生回调/自身期限，之后才能运行下一项。取消等待不会关闭会话。
 * 原生操作超时则使会话失效；[close] 终止资源而不是仅取消单次等待。
 */
interface GattSession {
    /** Android 进程内递增的会话标识；不作为持久化设备身份或重连复用依据。 */
    val id: Long
    /** 本会话所属设备；关闭本会话不关闭其管理器或其他设备的会话。 */
    val device: BtDevice
    /** 服务发现后固定的快照；从旧快照取得的句柄在重连后不可提交给新会话。 */
    val services: List<GattService>
    /** 当前协商 ATT MTU，单位字节，Android 初始为 23；写入负载上限为此值减 3。 */
    val mtu: StateFlow<Int>
    /** 无重放的原始特征变化；不做协议解码，也不代替 [BtGatt.state]。 */
    val events: SharedFlow<GattEvent>
    /**
     * 读取具有 [GattProperty.READ] 的本会话特征，返回独立复制的原始字节。
     * @param characteristic 从本会话 [services] 取得的句柄。
     * @return 成功原生读回调中的值；不是通知缓存。
     * @throws BtException.InvalidGattHandle 外部或其他会话句柄。
     * @throws BtException.UnsupportedOperation 无读属性。
     * @throws BtException.Timeout 原生读超过 10 秒，并使会话失效。
     * @throws BtException 权限、断连、关闭、拒绝或原生回调失败。
     */
    suspend fun read(characteristic: GattCharacteristic): ByteArray
    /**
     * 使用带响应写入（WITH_RESPONSE）发送一个完整负载；不自动分片或等待上层协议 ACK。
     *
     * Android 在入队前复制 [value]，提交时再次检查大小；需要 [GattProperty.WRITE]，
     * 仅有 [GattProperty.WRITE_NO_RESPONSE] 不够。成功只证明原生写回调成功。
     * @param characteristic 本会话的可带响应写特征。
     * @param value 单包负载，长度不得超过当前 [mtu] 减 3。
     * @throws BtException.InvalidGattHandle 外部或其他会话句柄。
     * @throws BtException.UnsupportedOperation 无带响应写属性。
     * @throws BtException.PacketTooLarge 负载超过当前 MTU 允许的大小。
     * @throws BtException.Timeout 原生写超过 10 秒，并使会话失效。
     * @throws BtException 权限、断连、关闭、拒绝或原生回调失败。
     */
    suspend fun write(characteristic: GattCharacteristic, value: ByteArray)
    /**
     * 设置平台本地通知开关并写入 CCCD；需要 NOTIFY 或 INDICATE 属性及 CCCD。
     *
     * 同时具有两者时优先 NOTIFY。先收集 [events] 再启用，避免错过早期通知；
     * 该方法不是引用计数，多消费者不得假设自己的关闭订阅不会影响其他消费者。
     * @param characteristic 本会话的通知/指示特征。
     * @param enabled `true` 启用，`false` 写禁用值；失败不承诺回滚本地开关。
     * @throws BtException.InvalidGattHandle 外部或其他会话句柄。
     * @throws BtException.UnsupportedOperation 缺属性或 CCCD。
     * @throws BtException.Timeout 描述符写超过 10 秒，并使会话失效。
     * @throws BtException 权限、断连、关闭、本地设置、提交或回调失败。
     */
    suspend fun setNotifications(characteristic: GattCharacteristic, enabled: Boolean)
    /**
     * 提交 MTU 协商请求，返回原生成功回调的实际值，并更新 [mtu]；不保证等于请求值。
     * @param value 请求的 ATT MTU 字节数，范围 23..517，默认 247。
     * @return 实际协商值。
     * @throws IllegalArgumentException 请求值不在 23..517。
     * @throws BtException.Timeout 原生请求超过 5 秒，并使会话失效。
     * @throws BtException 权限、断连、关闭、提交拒绝或回调失败。
     */
    suspend fun requestMtu(value: Int = 247): Int
    /**
     * 同步且幂等地标记失效、失败化等待、取消队列/通知发布并尽力 disconnect/close 原生资源。
     * 仅仍为设备当前会话时才改变其 GATT 状态；旧会话关闭不能覆盖替代会话。
     * 不关闭管理器或其他设备，不取消系统配对。
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
     * 单设备 GATT 状态变化，等价事实以 [BtGatt.state] 为准。
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
/** 单会话的原始 GATT 数据事件；无重放，不承担连接状态或请求/响应匹配。 */
sealed interface GattEvent {
    /**
     * 平台通知/指示中的完整原始值，不自动解码、拼包或识别 ACK。
     * @property characteristic 当前会话中产生变化的特征句柄。
     * @property value 从平台缓冲复制的字节；事件向多个收集者共享，不应再原地修改。
     */
    data class ValueChanged(val characteristic: GattCharacteristic, val value: ByteArray) : GattEvent {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ValueChanged) return false
            return characteristic == other.characteristic && value === other.value
        }

        override fun hashCode(): Int = 31 * characteristic.hashCode() + value.contentHashCode()
    }
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
