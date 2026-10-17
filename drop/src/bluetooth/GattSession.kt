package ink.lipoly.app.sunrise.bluetooth

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

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
interface GattController {
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
    /** 无重放的原始特征变化；不做协议解码，也不代替 [GattController.state]。 */
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
