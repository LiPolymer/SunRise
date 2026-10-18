package ink.lipoly.app.sunrise.drop

/**
 * 当前 READY 绑定的跨协议基础门面：只承诺各协议都能表达的基础能力，品牌或系列特有操作
 * 由各自的公开控件接口提供，调用方用 `as?` 取得，不在此处堆叠空实现。
 *
 * 门面是并列接口，不继承 [GaiaControls] / [SourceControls]；实现由绑定内的投影提供，
 * 不含协议编解码。门面不拥有会话生命周期：换会话后必须重新从控制器取得，旧引用读取能力
 * 或调用操作都抛 [DropException.Disconnected]。
 */
interface EarbudControls {
    /** 本绑定当前的能力投影；探测未完成时仍可读取，空集合不证明不支持。 */
    val capabilities: EarbudCapabilities
    /** 电量读取；协议不报告电量时为 null，不提供返回构造值的空实现。 */
    val battery: BatteryControls?
    /** 降噪模式读写；协议没有 ANC 语义时为 null。 */
    val noise: NoiseControls?
}

/**
 * 跨协议能力快照；不表示品牌认证，也不是每项命令成功的保证。
 * @property protocols 本会话识别的协议集合。
 * @property noiseModes 设备可写或已确认的降噪模式集合；空集合不等于设备没有 ANC。
 * @property complete 所有已存在协议的能力探测均完成；false 时集合仍在填充。
 */
data class EarbudCapabilities(
    val protocols: Set<DropProtocol> = emptySet(),
    val noiseModes: Set<AncMode> = emptySet(),
    val complete: Boolean = false,
)

/** 电量读取；成功返回后同时更新 [DropState.battery]，与协议通知同源。 */
interface BatteryControls {
    /** 读取并合并电量组件；负载非法抛 [DropException.Protocol]，旧引用抛 [DropException.Disconnected]。 */
    suspend fun read(): EarbudBatteryReading
}

/**
 * 降噪模式读写。写入返回读回确认的逻辑模式，不承诺设备已持久化。
 * 协议只能写入而无法确认时抛 [DropException.Unverified]，[DropException.AncModeMismatch]
 * 保留最后实际观察值；两者都不表示写入成功。
 */
interface NoiseControls {
    /** 读取当前模式；设备返回未映射编号时抛 [DropException.Protocol]。 */
    suspend fun read(): AncMode
    /** 写入 [mode] 并返回读回值；模式不在设备可写集合内时抛 [DropException.UnsupportedCapability]。 */
    suspend fun write(mode: AncMode): AncMode
}
