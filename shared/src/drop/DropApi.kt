package ink.lipoly.app.sunrise.drop

/**
 * 协议控制阶段，不表示蓝牙连接阶段。
 * IDLE：没有绑定；PROBING：订阅响应并探测；READY：至少存在一种可用协议；
 * ERROR：本会话初始化失败。READY 不保证 [DropCapabilities.complete] 或所有功能可用。
 */
enum class DropPhase { IDLE, PROBING, READY, ERROR }

/** 当前会话具有命令/响应特征对的协议：GAIA_BLE 为 GAIA GATT，SOURCE_9ECA 为 9ECA GATT。 */
enum class DropProtocol { GAIA_BLE, SOURCE_9ECA }
/** ANC 逻辑模式；实际支持集合与设备读写编号由 [DropCapabilities.ancModes] 和 [DropProfile] 决定。 */
enum class AncMode { OFF, NOISE_CANCELLING, TRANSPARENCY, WIND, ADAPTIVE, LIVE }
/** DAC 逻辑增益档位 LOW/MEDIUM/HIGH，不代表固定 dB；设备编号由配置映射。 */
enum class GainLevel { LOW, MEDIUM, HIGH }
/** 头部追踪模式；协议编号依次为 OFF=0、THIRTY_DEGREES=1、SURROUND=2。 */
enum class HeadTrackingMode { OFF, THIRTY_DEGREES, SURROUND }
/** 可查询启用开关的 LC3、LDAC、LHDC；开关结果不表示当前音频链路正在使用该编码。 */
enum class AudioCodec { LC3, LDAC, LHDC }
/** 序列号查询的耳侧：LEFT 或 RIGHT。 */
enum class EarbudSide { LEFT, RIGHT }

/**
 * 最近获知的电量。GAIA 按组件编号 1/2/3 解析无符号字节，不裁剪到 100；
 * 未出现的组件保留最近值，断连后恢复未知。
 * @property left 左耳原始百分比值，null 表示未知。
 * @property right 右耳原始百分比值，null 表示未知。
 * @property case 充电盒原始百分比值，null 表示未知。
 */
data class EarbudBattery(val left: Int? = null, val right: Int? = null, val case: Int? = null)

/**
 * 本会话探测所得能力，不是设备品牌认证，也不是每项命令成功的保证。
 * @property gaiaFeatures GAIA 能力页及 ANC fallback 探测发现的 feature ID。
 * @property ancModes 当前 ANC 路径及配置的可写逻辑模式集合。
 * @property sourceFeatures 固件 featureFlags 解出的 9ECA 功能集合。
 * @property complete 所有已存在协议的能力探测均完成；false 时仍可 READY，空集合不证明不支持。
 */
data class DropCapabilities(
    val gaiaFeatures: Set<Int> = emptySet(),
    val ancModes: Set<AncMode> = emptySet(),
    val sourceFeatures: Set<SourceFeature> = emptySet(),
    val complete: Boolean = false,
)

/** 9ECA 固件位标志可识别的功能：音源、音量、预设 EQ、参数 EQ、麦克风增益。 */
enum class SourceFeature { AUDIO_SOURCE, VOLUME, PRESET_EQ, PEQ, MIC_GAIN }

/**
 * 控制器当前会话的功能快照；未知值用 null 表示，断连/关闭重置，不包含音频设备选择。
 * @property phase 协议初始化阶段。
 * @property protocols 本会话识别的命令/响应特征对。
 * @property capabilities 最近探测的能力及完整性。
 * @property battery GAIA 电量查询或电量帧更新的最近值。
 * @property ancMode ANC GET 确认的逻辑模式；无法验证 SET 时清为 null，失配时保留实际读回。
 * @property gain DAC GET 读回值。
 * @property ledOn LED GET 读回值。
 * @property spatialOn 空间音效 GET 读回值。
 * @property headTracking 头部追踪 GET 读回值。
 * @property sourceStatus 9ECA 查询、切换响应或通知的音源状态。
 * @property volume 9ECA 音量查询、设置响应或通知值。
 * @property presetEq 9ECA 预设 EQ 查询/通知值；设置仅更新已存在快照的 current。
 * @property micGain 9ECA 麦克风增益查询、设置响应或通知值。
 * @property error 初始化失败原因；普通控件调用异常直接抛给调用方，不自动写入此字段。
 */
data class DropState(
    val phase: DropPhase = DropPhase.IDLE,
    val protocols: Set<DropProtocol> = emptySet(),
    val capabilities: DropCapabilities = DropCapabilities(),
    val battery: EarbudBattery = EarbudBattery(),
    val ancMode: AncMode? = null,
    val gain: GainLevel? = null,
    val ledOn: Boolean? = null,
    val spatialOn: Boolean? = null,
    val headTracking: HeadTrackingMode? = null,
    val sourceStatus: SourceStatus? = null,
    val volume: SourceVolume? = null,
    val presetEq: SourcePresetEq? = null,
    val micGain: SourceMicGain? = null,
    val error: DropException? = null,
)

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
    data class SourceNotification(val commandId: Int, val payload: ByteArray) : DropEvent
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
     * ANC 已写入但后续 GET 无法确认；[DropState.ancMode] 清为 null，保留读回失败 cause。
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

/**
 * 协议配置选项；不负责发现、连接、品牌筛选或重试。
 * @property profileOverrides 自定义设备映射；匹配地址优先，同类按列表首项，缺省字段可继承内置配置。
 */
data class DropOptions(val profileOverrides: List<DropProfile> = emptyList())

/**
 * 当前 READY 绑定的 GAIA 控件；换会话后必须重新从 [DropController.gaia] 获取。
 * 除 ANC SET/关机/sendRaw 外，请求等待匹配回复；大部分 SET 随后独立 GET 读回。
 * 功能存在性不由 getter 保证，缺少 GAIA 时操作抛 [DropException.UnsupportedCapability]。
 */
interface GaiaControls {
    /** 读取组件 ID/电量字节对并合并到 state.battery；空、奇数长度负载抛 Protocol。 */
    suspend fun getBattery(): EarbudBattery
    /** 用当前 ANC 路径和读映射获取模式、更新 state.ancMode；未知编号抛 Protocol。 */
    suspend fun getAncMode(): AncMode
    /**
     * 只发送一次 SET，不等待 SET ACK，随后最多四次 GET 确认。
     * @param mode 必须存在于当前配置可写模式；否则抛 UnsupportedCapability。
     * @return 确认的逻辑模式；Unverified 清空状态，AncModeMismatch 保留实际读回。
     */
    suspend fun setAncMode(mode: AncMode): AncMode
    /** 读取 DAC 编号、按配置逆映射，更新 state.gain；未知编号抛 Protocol。 */
    suspend fun getGain(): GainLevel
    /** 按配置写入 [level]，等待 SET 回复后 GET 读回；返回并保存实际档位。 */
    suspend fun setGain(level: GainLevel): GainLevel
    /** 读取 LED 开关，非零为 true，更新 state.ledOn。 */
    suspend fun isLedOn(): Boolean
    /** 写入 0/1，等待 SET 回复后 GET 读回，返回并保存实际 LED 开关。 */
    suspend fun setLedOn(on: Boolean): Boolean
    /** 读取空间音效开关，非零为 true，更新 state.spatialOn。 */
    suspend fun isSpatialOn(): Boolean
    /** 写入 0/1，等待 SET 回复后 GET 读回，返回并保存实际空间音效开关。 */
    suspend fun setSpatialOn(on: Boolean): Boolean
    /** 读取 0..2 的头部追踪模式并更新 state.headTracking；未知编号抛 Protocol。 */
    suspend fun getHeadTracking(): HeadTrackingMode
    /** 写入枚举编号、等待 SET 回复后 GET 读回，返回并保存实际追踪模式。 */
    suspend fun setHeadTracking(mode: HeadTrackingMode): HeadTrackingMode
    /** 查询 [codec] 开关，非零为 true；不更新 DropState，也不证明当前音频编码。 */
    suspend fun isCodecEnabled(codec: AudioCodec): Boolean
    /** 设置 [codec] 开关，等待 SET 回复后 GET 读回；不更新 DropState。 */
    suspend fun setCodecEnabled(codec: AudioCodec, enabled: Boolean): Boolean
    /** 查询动态低音开关，非零为 true；不更新 DropState。 */
    suspend fun isDynamicBassOn(): Boolean
    /** 设置动态低音 0/1，等待 SET 回复后 GET 读回；不更新 DropState。 */
    suspend fun setDynamicBassOn(on: Boolean): Boolean
    /** 查询左右声道反转开关，非零为 true；不更新 DropState。 */
    suspend fun isLeftRightReversed(): Boolean
    /** 设置左右反转 0/1，等待 SET 回复后 GET 读回；不更新 DropState。 */
    suspend fun setLeftRightReversed(reversed: Boolean): Boolean
    /** 返回 EQ 预设的无符号字节编号；不推断预设名称，不更新 DropState。 */
    suspend fun getEqualizerPreset(): Int
    /** 设置 [index]（0..255），等待 SET 回复后 GET 读回；越界抛 IllegalArgumentException。 */
    suspend fun setEqualizerPreset(index: Int): Int
    /** 查询原始手势配置；[gesture]、[context] 均须为 0..255，返回未进一步解释的包。 */
    suspend fun getGestureConfiguration(gesture: Int, context: Int): GaiaPacket
    /** 请求恢复手势配置并返回匹配响应；不声明设备已持久化或读回验证。 */
    suspend fun resetGestureConfiguration(): GaiaPacket
    /**
     * 查询基础信息，返回原始响应，不更新状态。
     * @param command 仅接受 [GaiaIds.Basic] 的 VERSION/FEATURES/SERIAL/VARIANT/APP_VERSION/
     * COLOR/LANGUAGE/LEFT_SN/RIGHT_SN/TWS_STATUS；其他值抛 IllegalArgumentException。
     */
    suspend fun getBasicInfo(command: Int): GaiaPacket
    /**
     * 查询 AUDIO_CURATION 原始数据，不自动解释或写入状态。
     * @param command 允许 0,2,3,5,7,8,10,12,13,15,17,18,20,22,23,25,26,28,30,31,33,34,35,37,38,39,41。
     */
    suspend fun getAudioCuration(command: Int): GaiaPacket
    /**
     * 设置 AUDIO_CURATION 原始数据，仅等待匹配响应，不另做 GET 确认。
     * @param command 允许 1,4,6,9,11,14,16,19,21,24,27,29,32,36,40,42。
     * @param payload 必须非空；提交前复制，不解释字段单位。
     */
    suspend fun setAudioCuration(command: Int, payload: ByteArray): GaiaPacket
    /** 仅发送关机命令；返回表示传输写完成，不表示耳机已经关机，不主动断开 GATT。 */
    suspend fun powerOff()
    /** 编码并写入 [command]，等待同 vendor/feature/command 的 RESPONSE；不额外解释状态字节。 */
    suspend fun requestRaw(command: GaiaCommand): GaiaPacket
    /** 编码并写入 [command] 后返回，不等待协议响应，不证明命令已应用。 */
    suspend fun sendRaw(command: GaiaCommand)
}

/**
 * 当前 READY 绑定的 9ECA 控件。响应首字节是状态码；一般非零抛 Rejected，
 * 音源 SET 的 5/9 例外。SET 返回响应值，并非一律独立 GET 验证。
 */
interface SourceControls {
    /** 查询并保存音源状态；普通请求首字节非零抛 Rejected。 */
    suspend fun getAudioSource(): SourceStatus
    /**
     * 发送音源切换，先保存响应，必要时每 200ms 查询直到稳定或超时。
     * @param sourceId 0..255，应来自 [SourceEntry.sourceId]，不能从编号猜测设备支持。
     * @param options fadeSeconds 须为 0..60；awaitStable 时期限为 (max(fadeSeconds,1)+3) 秒。
     * @return 响应/等待结果；稳定成功需 statusCode 为 0/9、transitionState=0 且 currentSource 匹配。
     * 等待遇到非 0/5/9 会退出，最终非 0/9 抛 Rejected；9 的返回不一定已稳定，仍应检查字段。
     */
    suspend fun setAudioSource(sourceId: Int, options: SourceSwitchOptions = SourceSwitchOptions()): SourceStatus
    /** 查询 [page]（0..15）；每页最多五项。空 entries 合法，不代表可以硬编码音源。 */
    suspend fun getCapabilityPage(page: Int): SourceCapabilityPage
    /** 直接读取可读 capability 特征（最多八项）；特征缺失/不可读抛 UnsupportedCapability。 */
    suspend fun readSourceCapability(): SourceCapability
    /** 优先读取 info 特征；解析/读取失败时退到 GET_FW_VERSION，取消透传；更新 sourceFeatures。 */
    suspend fun getFirmwareInfo(): SourceFirmwareInfo
    /** 查询音量并保存响应字段；范围/步进由设备提供，不假定百分比或 dB。 */
    suspend fun getVolume(): SourceVolume
    /** [mode]、[value]、[flags] 均须为 0..255；返回 SET 响应并保存 volume，不额外 GET。 */
    suspend fun setVolume(mode: Int, value: Int, flags: Int = 0): SourceVolume
    /** 查询并保存当前预设、数量及可编辑预设编号。 */
    suspend fun getPresetEq(): SourcePresetEq
    /** 设置 [index]（0..255），返回响应的前后编号；仅更新已有 state.presetEq.current，不额外 GET。 */
    suspend fun setPresetEq(index: Int): SourcePresetEqChange
    /** 查询用户 PEQ 的预设、点数、revision、前置增益和 dirty；不写入 DropState。 */
    suspend fun getPeqConfig(): SourcePeqConfig
    /** 设置用户预设 7 的前置增益，[centiDb] 为百分之一 dB，范围 -12800..12799；返回 ACK 字段。 */
    suspend fun setPeqPreGain(centiDb: Int): SourcePeqPreGain
    /** 查询 [index]（0..31）的 PEQ 点；返回原始 Q/filter 数据，不更新 DropState。 */
    suspend fun getPeqPoint(index: Int): SourcePeqPoint
    /**
     * 写入并返回 PEQ 点响应，不额外 GET。
     * @param point index=0..31，frequencyHz=20..20000，gainCentiDb=-32768..32767，
     * qRaw=1..65535，filterId=0..7；越界抛 IllegalArgumentException，不推断 Q 缩放/滤波器名称。
     */
    suspend fun setPeqPoint(point: SourcePeqPoint): SourcePeqPoint
    /** 提交 PEQ，[action] 为 0..2、[revision] 为 0..65535；只返回设备响应，不推断动作语义。 */
    suspend fun commitPeq(action: Int, revision: Int): SourcePeqCommit
    /** 查询并保存麦克风增益；各字段单位为十分之一 dB。 */
    suspend fun getMicGain(): SourceMicGain
    /** 设置 [deciDb]（-1280..1280，十分之一 dB），保存 SET 响应，不额外 GET。 */
    suspend fun setMicGain(deciDb: Int): SourceMicGain
    /** 返回颜色的原始无符号字节 ID，不推断颜色名称。 */
    suspend fun getEarbudColor(): Int
    /** 返回语言的原始无符号字节 ID，不推断语言名称。 */
    suspend fun getEarbudLanguage(): Int
    /** 查询 [side] 的偏移 0/10 两个序列号块并拼成 20 字节；不解码文本，结构不匹配抛 Protocol。 */
    suspend fun getEarbudSerial(side: EarbudSide): ByteArray
    /** 等待 PING 成功状态回复；不更新状态，不证明其他功能受支持。 */
    suspend fun ping()
}
