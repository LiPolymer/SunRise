package ink.lipoly.app.sunrise.drop

/**
 * 音源切换参数，flags 按 bit0/bit2/bit3 编码。
 * @property persistDefault bit0，请求将选择作为默认值；不表示已验证持久化。
 * @property muteDuringSwitch bit2，请求切换期间静音。
 * @property noBluetoothAutoResume bit3，请求禁止蓝牙自动恢复。
 * @property fadeSeconds 淡入淡出秒数；setAudioSource 检查 0..60。
 * @property awaitStable 是否查询至稳定；false 直接返回首个 SET 响应。
 */
data class SourceSwitchOptions(
    val persistDefault: Boolean = false,
    val muteDuringSwitch: Boolean = false,
    val noBluetoothAutoResume: Boolean = false,
    val fadeSeconds: Int = 5,
    val awaitStable: Boolean = true,
) {
    internal fun flags(): Int = (if (persistDefault) 1 else 0) or
        (if (muteDuringSwitch) 4 else 0) or (if (noBluetoothAutoResume) 8 else 0)
}

/**
 * 9ECA 音源响应/通知中的五个无符号字节；不把状态码等同于物理音频已切换。
 * @property statusCode 原始状态码；SET 接受 0/5/9，其他普通请求仅接受 0。
 * @property currentSource 当前音源 ID，应与能力 entries 关联。
 * @property targetSource 目标音源 ID。
 * @property transitionState 原始过渡状态，0 才视作稳定。
 * @property activeReason 原始活动原因码，当前实现不映射名称。
 */
data class SourceStatus(
    val statusCode: Int, val currentSource: Int, val targetSource: Int,
    val transitionState: Int, val activeReason: Int,
) {
    /** statusCode 为 0 或 9 且 transitionState=0；不检查当前音源是否等于调用方目标。 */
    val stableSuccess: Boolean get() = statusCode in setOf(0, 9) && transitionState == 0
}

/**
 * 设备报告的音源项；不建立全局编号表，不解释未定义位。
 * @property sourceId 可供 setAudioSource 使用的无符号字节 ID。
 * @property flags 设备原始逐项位标志，当前实现不解码。
 */
data class SourceEntry(val sourceId: Int, val flags: Int)
/**
 * 命令能力页，负载含状态、页号、总页数、项数及成对条目。
 * @property page 返回页号（字节），不由 codec 验证等于请求页。
 * @property totalPages 设备报告的总页数（字节），不可盲目超过调用 API 的 0..15 范围。
 * @property entries 本页最多五项，空集合合法。
 */
data class SourceCapabilityPage(val page: Int, val totalPages: Int, val entries: List<SourceEntry>)
/**
 * 直接读取 capability 特征的结构，不带命令响应状态字节。
 * @property version 原始结构版本字节。
 * @property currentSource 当前音源 ID。
 * @property globalFlags 原始全局标志，当前实现不解码。
 * @property entries 最多八个音源项，空集合合法。
 */
data class SourceCapability(
    val version: Int, val currentSource: Int, val globalFlags: Int, val entries: List<SourceEntry>,
)
/**
 * 12 字节固件信息；直接特征从 offset=0 解码，命令回复通常跳过状态字节。
 * @property protocolMajor 协议主版本字节。
 * @property protocolMinor 协议次版本字节。
 * @property featureFlags 小端无符号 16 位功能标志。
 * @property major 固件主版本字节。
 * @property minor 固件次版本字节。
 * @property patch 固件修订版本字节。
 * @property buildType 原始构建类型字节，不映射名称。
 * @property buildId 小端无符号 32 位构建号，以 Long 保存。
 */
data class SourceFirmwareInfo(
    val protocolMajor: Int, val protocolMinor: Int, val featureFlags: Int,
    val major: Int, val minor: Int, val patch: Int, val buildType: Int, val buildId: Long,
) {
    /** bit0=PRESET_EQ、bit1=PEQ、bit2=AUDIO_SOURCE、bit3=VOLUME、bit4=MIC_GAIN；忽略其余位。 */
    val features: Set<SourceFeature> get() = buildSet {
        if (featureFlags and 1 != 0) add(SourceFeature.PRESET_EQ)
        if (featureFlags and 2 != 0) add(SourceFeature.PEQ)
        if (featureFlags and 4 != 0) add(SourceFeature.AUDIO_SOURCE)
        if (featureFlags and 8 != 0) add(SourceFeature.VOLUME)
        if (featureFlags and 16 != 0) add(SourceFeature.MIC_GAIN)
    }
}
/**
 * 设备定义刻度的音量值，不自动解释为百分比或 dB；均为无符号字节。
 * @property current 当前刻度。
 * @property minimum 设备报告的最小刻度。
 * @property maximum 设备报告的最大刻度。
 * @property step 设备报告的步进。
 * @property muteState 原始静音状态码，不转 Boolean。
 */
data class SourceVolume(
    val current: Int, val minimum: Int, val maximum: Int, val step: Int, val muteState: Int,
)
/**
 * 预设 EQ 查询值，均为无符号字节，不推断预设名称。
 * @property current 当前预设编号。
 * @property count 预设数量。
 * @property editablePreset 可编辑预设编号。
 */
data class SourcePresetEq(val current: Int, val count: Int, val editablePreset: Int)
/**
 * 预设 EQ 设置响应，不是独立读回验证。
 * @property current 设置响应中的当前编号。
 * @property previous 设置响应中的先前编号。
 * @property editablePreset 可编辑预设编号。
 */
data class SourcePresetEqChange(val current: Int, val previous: Int, val editablePreset: Int)
/**
 * PEQ 配置查询结果。
 * @property preset 预设编号字节。
 * @property pointCount 点数字节，不代表所有编号均被 API 接受。
 * @property revision 小端无符号 16 位修订号。
 * @property preGainCentiDb 小端有符号 16 位前置增益，百分之一 dB。
 * @property dirty 原始 dirty 字节非零为 true。
 */
data class SourcePeqConfig(
    val preset: Int, val pointCount: Int, val revision: Int, val preGainCentiDb: Int, val dirty: Boolean,
)
/**
 * 前置增益 SET 响应。
 * @property preset 响应预设编号字节。
 * @property centiDb 小端有符号 16 位增益，百分之一 dB。
 * @property dirty 原始 dirty 字节非零为 true。
 */
data class SourcePeqPreGain(val preset: Int, val centiDb: Int, val dirty: Boolean)
/**
 * 单个 PEQ 点；模型构造不校验，写入范围由 [SourceControls.setPeqPoint] 校验。
 * @property index 点编号，写入须 0..31。
 * @property frequencyHz 无符号 16 位频率，Hz；写入须 20..20000。
 * @property gainCentiDb 有符号 16 位增益，百分之一 dB。
 * @property qRaw 无符号 16 位 Q 原始值；写入须 1..65535，不假定缩放公式。
 * @property filterId 滤波器原始编号字节，写入须 0..7，不映射名称。
 */
data class SourcePeqPoint(
    val index: Int, val frequencyHz: Int, val gainCentiDb: Int, val qRaw: Int, val filterId: Int,
)
/**
 * PEQ 提交响应，不推断 commit action 的固件含义。
 * @property revision 无符号 16 位修订号。
 * @property activePreset 当前预设编号字节。
 * @property dirty dirty 字节非零为 true。
 */
data class SourcePeqCommit(val revision: Int, val activePreset: Int, val dirty: Boolean)
/**
 * 麦克风增益，全部为小端有符号 16 位值，单位十分之一 dB。
 * @property currentDeciDb 当前增益。
 * @property minDeciDb 设备报告的下界。
 * @property maxDeciDb 设备报告的上界。
 * @property stepDeciDb 设备报告的步进。
 */
data class SourceMicGain(
    val currentDeciDb: Int, val minDeciDb: Int, val maxDeciDb: Int, val stepDeciDb: Int,
)

/**
 * 六字节头之后的已解码帧；ByteArray 不承诺内容相等语义。
 * @property type RESPONSE=2 或 NOTIFICATION=3。
 * @property commandId 无符号字节命令编号。
 * @property sequence 无符号字节序号，响应需与当前请求匹配。
 * @property payload 独立复制的负载，最多 14 字节；不含帧头。
 */
data class SourceFrame(val type: Int, val commandId: Int, val sequence: Int, val payload: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SourceFrame) return false
        return type == other.type && commandId == other.commandId &&
            sequence == other.sequence && payload === other.payload
    }

    override fun hashCode(): Int {
        var result = type
        result = 31 * result + commandId
        result = 31 * result + sequence
        return 31 * result + payload.contentHashCode()
    }
}

/** 9ECA 命令编号命名空间；存在常量不表示设备支持，能力与拒绝状态以设备响应为准。 */
object SourceIds {
    /** 前置增益写入固定选用的用户 PEQ 预设编号。 */
    const val USER_PEQ_PRESET = 7
    /** 查询音源五字节状态的命令。 */
    const val GET_AUDIO_SOURCE = 1
    /** 写入 sourceId、flags、fadeSeconds 三字节并返回音源状态的命令。 */
    const val SET_AUDIO_SOURCE = 2
    /** 查询分页音源 entries 的命令，负载是一个页号字节。 */
    const val GET_CAPABILITY = 3
    /** 读取固件信息的 fallback 命令，响应首字节为状态。 */
    const val GET_FW_VERSION = 4
    /** 查询设备音量刻度、范围、步进、静音状态的命令。 */
    const val GET_VOLUME = 5
    /** 写入 mode/value/flags 三字节音量参数的命令。 */
    const val SET_VOLUME = 6
    /** 查询预设编号、数量和可编辑编号的命令。 */
    const val GET_PRESET_EQ = 7
    /** 写入单字节 EQ 预设编号的命令。 */
    const val SET_PRESET_EQ = 8
    /** 查询 PEQ revision、前置增益及 dirty 状态的命令。 */
    const val GET_PEQ_CONFIG = 9
    /** 写入用户预设编号和小端有符号前置增益的命令。 */
    const val SET_PEQ_PREGAIN = 10
    /** 按单字节点编号读取 PEQ 点的命令。 */
    const val GET_PEQ_POINT = 11
    /** 写入频率、增益、Q 和滤波器编号的命令。 */
    const val SET_PEQ_POINT = 12
    /** 写入 action 字节和小端 revision 的 PEQ 提交命令。 */
    const val COMMIT_PEQ = 13
    /** 查询麦克风增益及边界、步进的命令。 */
    const val GET_MIC_GAIN = 14
    /** 写入小端有符号麦克风增益的命令。 */
    const val SET_MIC_GAIN = 15
    /** 查询原始颜色编号的命令。 */
    const val GET_COLOR = 18
    /** 查询原始语言编号的命令。 */
    const val GET_LANGUAGE = 19
    /** 分块读取左耳 20 字节序列号的命令。 */
    const val GET_LEFT_SN = 20
    /** 分块读取右耳 20 字节序列号的命令。 */
    const val GET_RIGHT_SN = 21
    /** 仅检查成功状态字节的连通性命令。 */
    const val PING = 127
}

/**
 * 9ECA GATT 帧与负载编解码。多字节字段小端，响应解析器只校验最小长度，
 * 不自行验证状态码成功；[SourceControls] 在调用解析器前执行状态检查。
 */
object SourceCodec {
    /** 编码出的请求帧类型，数值 1。 */
    const val COMMAND = 1
    /** 接受的响应帧类型，数值 2，需以 commandId/sequence 匹配请求。 */
    const val RESPONSE = 2
    /** 接受的非请求通知帧类型，数值 3。 */
    const val NOTIFICATION = 3
    private const val HEADER = 6
    private const val MAX_PAYLOAD = 14

    /**
     * 编码 A5、版本1、COMMAND、命令、序号、长度和复制的负载。
     * @param commandId 0..255。
     * @param sequence 0..255，绑定按字节循环递增。
     * @param payload 最多 14 字节。
     * @throws IllegalArgumentException 编号或长度越界。
     */
    fun encode(commandId: Int, sequence: Int, payload: ByteArray = byteArrayOf()): ByteArray {
        require(commandId in 0..255 && sequence in 0..255 && payload.size <= MAX_PAYLOAD)
        return byteArrayOf(0xa5.toByte(), 1, COMMAND.toByte(), commandId.toByte(),
            sequence.toByte(), payload.size.toByte()) + payload.copyOf()
    }

    /**
     * 只接受版本1的 RESPONSE/NOTIFICATION，复制声明长度的负载。
     * @return 头/类型/长度非法为 null；额外尾字节忽略，负载不足为 null，不做拼帧。
     */
    fun decode(frame: ByteArray): SourceFrame? {
        if (frame.size < HEADER || (frame[0].toInt() and 0xff) != 0xa5 || frame[1].toInt() != 1) return null
        val length = frame[5].toInt() and 0xff
        if (length > MAX_PAYLOAD || frame.size < HEADER + length) return null
        val type = frame[2].toInt() and 0xff
        if (type != RESPONSE && type != NOTIFICATION) return null
        return SourceFrame(type, frame[3].toInt() and 0xff, frame[4].toInt() and 0xff,
            frame.copyOfRange(HEADER, HEADER + length))
    }

    /** 返回首字节无符号状态；空负载抛 [DropException.Protocol]，不判断成功。 */
    fun status(payload: ByteArray): Int = payload.firstOrNull()?.toInt()?.and(0xff)
        ?: throw DropException.Protocol("Empty 9ECA response")

    /** 解析至少五字节的状态/current/target/transition/reason，不检查稳定性。 */
    fun sourceStatus(payload: ByteArray): SourceStatus {
        requireLength(payload, 5)
        return SourceStatus(u8(payload, 0), u8(payload, 1), u8(payload, 2), u8(payload, 3), u8(payload, 4))
    }

    /** 解析至少四字节页头及最多五项 ID/flags；缺字节或项数越界抛 Protocol。 */
    fun capabilityPage(payload: ByteArray): SourceCapabilityPage {
        requireLength(payload, 4)
        val count = u8(payload, 3)
        if (count > 5) throw DropException.Protocol("Invalid 9ECA capability count")
        requireLength(payload, 4 + count * 2)
        return SourceCapabilityPage(u8(payload, 1), u8(payload, 2),
            (0 until count).map { SourceEntry(u8(payload, 4 + it * 2), u8(payload, 5 + it * 2)) })
    }

    /** 解析无响应状态字节的 capability 特征；最多八项，结构短缺/超项抛 Protocol。 */
    fun sourceCapability(bytes: ByteArray): SourceCapability {
        requireLength(bytes, 4)
        val count = u8(bytes, 1)
        if (count > 8) throw DropException.Protocol("Invalid 9ECA source count")
        requireLength(bytes, 4 + count * 2)
        return SourceCapability(u8(bytes, 0), u8(bytes, 2), u8(bytes, 3),
            (0 until count).map { SourceEntry(u8(bytes, 4 + it * 2), u8(bytes, 5 + it * 2)) })
    }

    /**
     * 从 [offset] 解析 12 字节固件数据；调用方应提供非负 offset。
     * 直接特征用 0，带状态命令负载用 1；字节不足抛 Protocol。
     */
    fun firmware(bytes: ByteArray, offset: Int = 0): SourceFirmwareInfo {
        requireLength(bytes, offset + 12)
        return SourceFirmwareInfo(u8(bytes, offset), u8(bytes, offset + 1), u16(bytes, offset + 2),
            u8(bytes, offset + 4), u8(bytes, offset + 5), u8(bytes, offset + 6),
            u8(bytes, offset + 7), u32(bytes, offset + 8))
    }

    /** 跳过状态字节，解析至少六字节的音量响应；不检查数值边界关系。 */
    fun volume(payload: ByteArray): SourceVolume {
        requireLength(payload, 6)
        return SourceVolume(u8(payload, 1), u8(payload, 2), u8(payload, 3), u8(payload, 4), u8(payload, 5))
    }

    /** 跳过状态字节，解析至少四字节的当前预设/数量/可编辑编号。 */
    fun presetEq(payload: ByteArray): SourcePresetEq {
        requireLength(payload, 4)
        return SourcePresetEq(u8(payload, 1), u8(payload, 2), u8(payload, 3))
    }

    /** 跳过状态字节，解析至少四字节的当前/先前/可编辑预设编号。 */
    fun presetChange(payload: ByteArray): SourcePresetEqChange {
        requireLength(payload, 4)
        return SourcePresetEqChange(u8(payload, 1), u8(payload, 2), u8(payload, 3))
    }

    /** 解析至少八字节 PEQ 配置，revision 无符号、增益有符号、dirty 非零为 true。 */
    fun peqConfig(payload: ByteArray): SourcePeqConfig {
        requireLength(payload, 8)
        return SourcePeqConfig(u8(payload, 1), u8(payload, 2), u16(payload, 3), i16(payload, 5), u8(payload, 7) != 0)
    }

    /** 解析至少五字节前置增益响应，dirty 非零为 true。 */
    fun peqPreGain(payload: ByteArray): SourcePeqPreGain {
        requireLength(payload, 5)
        return SourcePeqPreGain(u8(payload, 1), i16(payload, 2), u8(payload, 4) != 0)
    }

    /** 解析至少九字节 PEQ 点响应，不执行写入参数范围检查。 */
    fun peqPoint(payload: ByteArray): SourcePeqPoint {
        requireLength(payload, 9)
        return SourcePeqPoint(u8(payload, 1), u16(payload, 2), i16(payload, 4), u16(payload, 6), u8(payload, 8))
    }

    /** 解析至少五字节 PEQ 提交响应。 */
    fun peqCommit(payload: ByteArray): SourcePeqCommit {
        requireLength(payload, 5)
        return SourcePeqCommit(u16(payload, 1), u8(payload, 3), u8(payload, 4) != 0)
    }

    /** 跳过状态字节，解析至少九字节的四个有符号 16 位麦克风增益字段。 */
    fun micGain(payload: ByteArray): SourceMicGain {
        requireLength(payload, 9)
        return SourceMicGain(i16(payload, 1), i16(payload, 3), i16(payload, 5), i16(payload, 7))
    }

    /** 从至少两字节响应中返回第二字节的原始颜色/语言编号。 */
    fun earbudInfo(payload: ByteArray): Int { requireLength(payload, 2); return u8(payload, 1) }

    /**
     * 校验总长字段=20、块偏移=[expectedOffset]、块长=10 后复制十字节序列号。
     * 负载至少 14 字节；结构不匹配抛 Protocol，不检查首字节成功状态。
     */
    fun snChunk(payload: ByteArray, expectedOffset: Int): ByteArray {
        requireLength(payload, 14)
        if (u8(payload, 1) != 20 || u8(payload, 2) != expectedOffset || u8(payload, 3) != 10)
            throw DropException.Protocol("Invalid 9ECA serial chunk")
        return payload.copyOfRange(4, 14)
    }

    /** 读取 [offset] 的无符号字节；调用方负责边界，越界为数组异常。 */
    fun u8(bytes: ByteArray, offset: Int): Int = bytes[offset].toInt() and 0xff
    /** 从 [offset] 读取小端无符号 16 位值；调用方负责两字节边界。 */
    fun u16(bytes: ByteArray, offset: Int): Int = u8(bytes, offset) or (u8(bytes, offset + 1) shl 8)
    /** 从 [offset] 读取小端有符号 16 位值；调用方负责两字节边界。 */
    fun i16(bytes: ByteArray, offset: Int): Int = u16(bytes, offset).toShort().toInt()
    /** 从 [offset] 读取小端无符号 32 位值到 Long；调用方负责四字节边界。 */
    fun u32(bytes: ByteArray, offset: Int): Long =
        u8(bytes, offset).toLong() or (u8(bytes, offset + 1).toLong() shl 8) or
            (u8(bytes, offset + 2).toLong() shl 16) or (u8(bytes, offset + 3).toLong() shl 24)
    /** 输出小端低 16 位；[value] 接受 -32768..65535，越界抛 IllegalArgumentException。 */
    fun le16(value: Int): ByteArray {
        require(value in -32768..65535)
        return byteArrayOf(value.toByte(), (value shr 8).toByte())
    }

    private fun requireLength(bytes: ByteArray, min: Int) {
        if (bytes.size < min) throw DropException.Protocol("Short 9ECA response: ${bytes.size} < $min")
    }
}
