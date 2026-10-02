package ink.lipoly.app.sunrise.drop

/**
 * GAIA 请求模型；构造不校验，编码时才检查字段范围。
 * @property feature 0..127 的功能编号。
 * @property command 0..127 的功能内命令编号。
 * @property payload 原始负载，编码时复制；本模型不限制长度，实际写入受 GATT MTU 限制。
 * @property vendor 0..65535 厂商编号，默认 0x001D；响应必须与请求一致。
 */
data class GaiaCommand(
    val feature: Int,
    val command: Int,
    val payload: ByteArray = byteArrayOf(),
    val vendor: Int = 0x001D,
)

/**
 * GAIA 四字节头解码结果，不在 codec 中检查成功/拒绝状态。
 * @property vendor 大端 16 位厂商编号。
 * @property feature command word 的高七位功能编号。
 * @property type command word 的两位类型（0 命令、1 通知、2 响应；decode 也可返回 3）。
 * @property command command word 的低七位命令编号。
 * @property payload 头之后的独立负载副本；ByteArray 不承诺内容相等语义。
 */
data class GaiaPacket(
    val vendor: Int,
    val feature: Int,
    val type: Int,
    val command: Int,
    val payload: ByteArray,
)

/**
 * GAIA feature 与各 feature 内命令编号；命名存在不表示设备实现此功能。
 * [GaiaControls] 只为部分命令提供结构化方法；其余通过原始接口使用，不推断固件私有负载。
 */
object GaiaIds {
    /** 基础信息、能力分页与通知注册的 feature。 */
    const val BASIC = 0
    /** 耳机通用功能命名空间；当前没有专用结构化控件。 */
    const val EARBUD = 1
    /** 仅 OFF/NC 的第一版 ANC 路径。 */
    const val ANC_V1 = 2
    /** 语音交互功能命名空间；当前没有专用结构化控件。 */
    const val VOICE_UI = 3
    /** 调试功能命名空间；存在常量不开放调试操作。 */
    const val DEBUG = 4
    /** 音乐处理/EQ 预设的 feature。 */
    const val MUSIC_PROCESSING = 5
    /** 升级功能命名空间；当前不实现固件升级。 */
    const val UPGRADE = 6
    /** 手机服务命名空间；当前没有专用结构化控件。 */
    const val HANDSET_SERVICE = 7
    /** 音频调节及优先 ANC 探测路径。 */
    const val AUDIO_CURATION = 8
    /** 佩戴检测功能命名空间；当前没有专用结构化控件。 */
    const val EARBUD_FIT = 9
    /** 语音处理命名空间；当前没有专用结构化控件。 */
    const val VOICE_PROCESSING = 10
    /** 手势配置查询与恢复操作的 feature。 */
    const val GESTURE_CONFIGURATION = 11
    /** 统计功能命名空间；当前没有专用结构化控件。 */
    const val STATISTICS = 12
    /** 左耳/右耳/盒电量组件字节对的 feature。 */
    const val BATTERY = 13
    /** 语音功能命名空间；当前没有专用结构化控件。 */
    const val VOICE = 14
    /** DAC 三档增益的 feature；编号受设备 profile 影响。 */
    const val DAC_GAIN = 15
    /** LC3/LDAC/LHDC 启用开关的 feature。 */
    const val CODEC_TYPE = 16
    /** 光感命名空间；当前没有专用结构化控件。 */
    const val LIGHT_SENSOR = 17
    /** 空间音效及头部追踪的 feature。 */
    const val SPATIAL_AUDIO = 18
    /** LED 开关的 feature。 */
    const val LED = 19
    /** 一带二功能命名空间；当前没有专用结构化控件。 */
    const val ONE_BRING_TWO = 20
    /** 地址信息命名空间；与 BtDevice 地址句柄管理无关。 */
    const val BT_ADDRESS = 21
    /** 第二版触控功能命名空间；当前没有专用结构化控件。 */
    const val TOUCH_V2 = 22
    /** 音频资源命名空间；当前不实现资源传输。 */
    const val AUDIO_RESOURCE = 23
    /** 单向关机命令的 feature。 */
    const val POWER_CONTROL = 24
    /** 电源期限命名空间；当前没有专用结构化控件。 */
    const val POWER_TIMEOUT = 25
    /** 第三版触控功能命名空间；当前没有专用结构化控件。 */
    const val TOUCH_V3 = 26
    /** 动态低音开关的 feature。 */
    const val DYNAMIC_BASS = 27
    /** 音频文件存储命名空间；当前不实现文件传输。 */
    const val AUDIO_FILE_STORAGE = 29
    /** 左右声道反转开关的 feature。 */
    const val LR_CHANNEL = 30
    /** 第二版 ANC 路径，可由 profile 映射六种逻辑模式。 */
    const val ANC_V2 = 32

    /**
     * 基础信息命令；FEATURES/FEATURES_NEXT 用于能力分页。
     * VERSION/SERIAL/VARIANT/APP_VERSION/COLOR/LANGUAGE/LEFT_SN/RIGHT_SN/TWS_STATUS 可通过 getBasicInfo 查询，
     * REGISTER_NOTIFICATION/CANCEL_NOTIFICATION/DATA_SETUP/DATA_GET 仅保留编号，初始化不发送它们。
     */
    object Basic { /** 协议版本原始查询。 */ const val VERSION = 0; /** 首个能力页查询。 */ const val FEATURES = 1; /** 后续能力页查询。 */ const val FEATURES_NEXT = 2; /** 序列号原始查询。 */ const val SERIAL = 3; /** 设备变体原始查询。 */ const val VARIANT = 4; /** 应用版本原始查询。 */ const val APP_VERSION = 5; /** 原始通知注册编号；控制器不自动调用。 */ const val REGISTER_NOTIFICATION = 7; /** 原始通知取消编号；close 不发送。 */ const val CANCEL_NOTIFICATION = 8; /** 原始数据通道设置编号，当前无结构化接口。 */ const val DATA_SETUP = 9; /** 原始数据通道查询编号，当前无结构化接口。 */ const val DATA_GET = 10; /** 颜色原始查询。 */ const val COLOR = 18; /** 语言原始查询。 */ const val LANGUAGE = 19; /** 左耳序列号原始查询。 */ const val LEFT_SN = 20; /** 右耳序列号原始查询。 */ const val RIGHT_SN = 21; /** TWS 状态原始查询。 */ const val TWS_STATUS = 22 }
    /** ANC 命令空间：V1_GET/V1_SET 用于 V1；GET_MODE/SET_MODE 用于 V2；SWITCH_CONFIG 为原始配置。 */
    object Anc { /** V1 的 0/1 模式读回。 */ const val V1_GET = 1; /** V1 的 0/1 模式写入。 */ const val V1_SET = 2; /** V2 模式读回，编号由 profile 解码。 */ const val GET_MODE = 3; /** V2 模式写入，编号由 profile 编码。 */ const val SET_MODE = 4; /** 切换配置原始查询，无结构化解释。 */ const val SWITCH_CONFIG_GET = 41; /** 切换配置原始写入，无读回保证。 */ const val SWITCH_CONFIG_SET = 42 }
    /**
     * AUDIO_CURATION 的 0..42 原始命令编号，涉及状态、模式、增益、切换、场景与各设备特性。
     * GET_MODE/SET_MODE 供 ANC 控件使用，其余使用 get/setAudioCuration；不推断负载单位或支持性。
     */
    object AudioCuration { /** 音频调节状态原始查询。 */ const val GET_STATE = 0; /** 音频调节状态原始设置。 */ const val SET_STATE = 1; /** 模式数量原始查询。 */ const val GET_MODE_COUNT = 2; /** ANC 模式读回，使用 profile 读映射。 */ const val GET_MODE = 3; /** ANC 模式写入，使用 profile 写映射。 */ const val SET_MODE = 4; /** 音频调节增益原始查询，非 DAC 档位。 */ const val GET_GAIN = 5; /** 音频调节增益原始设置，单位不解码。 */ const val SET_GAIN = 6; /** 切换项数量原始查询。 */ const val GET_TOGGLE_COUNT = 7; /** 切换项原始查询。 */ const val GET_TOGGLE = 8; /** 切换项原始设置。 */ const val SET_TOGGLE = 9; /** 场景原始查询。 */ const val GET_SCENARIO = 10; /** 场景原始设置。 */ const val SET_SCENARIO = 11; /** 演示支持原始查询。 */ const val GET_DEMO_SUPPORT = 12; /** 演示状态原始查询。 */ const val GET_DEMO_STATE = 13; /** 演示状态原始设置。 */ const val SET_DEMO_STATE = 14; /** 自适应原始查询。 */ const val GET_ADAPTATION = 15; /** 自适应原始设置。 */ const val SET_ADAPTATION = 16; /** 透传配置原始查询。 */ const val GET_LEAKTHROUGH_CONFIG = 17; /** 透传步进原始查询。 */ const val GET_LEAKTHROUGH_STEP = 18; /** 透传步进原始设置。 */ const val SET_LEAKTHROUGH_STEP = 19; /** 平衡原始查询。 */ const val GET_BALANCE = 20; /** 平衡原始设置。 */ const val SET_BALANCE = 21; /** 抗风支持原始查询。 */ const val GET_WIND_SUPPORT = 22; /** 抗风状态原始查询。 */ const val GET_WIND_STATE = 23; /** 抗风状态原始设置。 */ const val SET_WIND_STATE = 24; /** 自动通透支持原始查询。 */ const val GET_AUTO_TRANSPARENCY_SUPPORT = 25; /** 自动通透状态原始查询。 */ const val GET_AUTO_TRANSPARENCY_STATE = 26; /** 自动通透状态原始设置。 */ const val SET_AUTO_TRANSPARENCY_STATE = 27; /** 释放时间原始查询，单位不解码。 */ const val GET_RELEASE_TIME = 28; /** 释放时间原始设置，单位不校验。 */ const val SET_RELEASE_TIME = 29; /** 啸叫功能支持原始查询。 */ const val GET_HOWLING_SUPPORT = 30; /** 啸叫功能状态原始查询。 */ const val GET_HOWLING_STATE = 31; /** 啸叫功能状态原始设置。 */ const val SET_HOWLING_STATE = 32; /** 反馈增益原始查询，单位不解码。 */ const val GET_FEEDBACK_GAIN = 33; /** 噪声识别支持原始查询。 */ const val GET_NOISE_ID_SUPPORT = 34; /** 噪声识别状态原始查询。 */ const val GET_NOISE_ID_STATE = 35; /** 噪声识别状态原始设置。 */ const val SET_NOISE_ID_STATE = 36; /** 噪声分类原始查询，不映射类别名称。 */ const val GET_NOISE_CATEGORY = 37; /** 不利条件功能支持原始查询。 */ const val GET_ADVERSE_SUPPORT = 38; /** 不利条件功能状态原始查询。 */ const val GET_ADVERSE_STATE = 39; /** 不利条件功能状态原始设置。 */ const val SET_ADVERSE_STATE = 40; /** 模式切换配置原始查询。 */ const val GET_SWITCH_CONFIG = 41; /** 模式切换配置原始设置。 */ const val SET_SWITCH_CONFIG = 42 }
    /** 音乐处理命令；结构化控件只使用 GET_PRESET/SET_PRESET，其余编号供原始请求。 */
    object Eq { /** EQ 状态原始查询。 */ const val GET_STATE = 0; /** 预设集合原始查询。 */ const val GET_PRESETS = 1; /** 当前单字节预设查询。 */ const val GET_PRESET = 2; /** 单字节预设设置。 */ const val SET_PRESET = 3; /** 频段数量原始查询。 */ const val GET_BAND_COUNT = 4; /** 用户配置原始查询。 */ const val GET_USER_CONFIG = 5; /** 用户配置原始设置。 */ const val SET_USER_CONFIG = 6; /** 用户配置存储原始编号，不声明持久化已完成。 */ const val STORE_USER_CONFIG = 7; /** NV 编号原始设置。 */ const val SET_NV_ID = 8 }
    /** 手势命令；结构化控件仅使用 GET_CONFIG 与 RESET，其余负载由设备协议定义。 */
    object Gesture { /** 触摸面数量原始查询。 */ const val TOUCHPAD_COUNT = 0; /** 支持手势集合原始查询。 */ const val SUPPORTED_GESTURES = 1; /** 支持上下文集合原始查询。 */ const val SUPPORTED_CONTEXTS = 2; /** 支持动作集合原始查询。 */ const val SUPPORTED_ACTIONS = 3; /** gesture/context 两字节配置查询。 */ const val GET_CONFIG = 4; /** 手势配置原始设置。 */ const val SET_CONFIG = 5; /** 配置恢复请求，仅等待匹配响应。 */ const val RESET = 6 }
    /** 编码启用开关的 GET/SET 命令，独立于当前音频协商结果。 */
    object Codec { /** LC3 开关查询。 */ const val GET_LC3 = 1; /** LDAC 开关查询。 */ const val GET_LDAC = 2; /** LC3 0/1 开关写入。 */ const val SET_LC3 = 3; /** LDAC 0/1 开关写入。 */ const val SET_LDAC = 4; /** LHDC 开关查询。 */ const val GET_LHDC = 5; /** LHDC 0/1 开关写入。 */ const val SET_LHDC = 6 }
    /** 空间音效 GET/SET 与头部追踪 GET_TRACKING/SET_TRACKING 的功能内命令。 */
    object Spatial { /** 空间音效开关查询。 */ const val GET = 1; /** 空间音效 0/1 开关写入。 */ const val SET = 2; /** 头部追踪 0..2 编号查询。 */ const val GET_TRACKING = 3; /** 头部追踪枚举编号写入。 */ const val SET_TRACKING = 4 }
    /** 多个 feature 共用的 GET=1/SET=2；POWER_OFF=1 仅用于 POWER_CONTROL。 */
    object Device { /** 所属 feature 的单值查询。 */ const val GET = 1; /** 所属 feature 的单值设置。 */ const val SET = 2; /** POWER_CONTROL 单向关机请求。 */ const val POWER_OFF = 1 }
    /** 电量命令：LEVELS 返回组件/数值对；SUPPORTED 只保留原始编号。 */
    object Battery { /** 电量支持信息原始查询，getBattery 不使用。 */ const val SUPPORTED = 0; /** 组件编号与无符号电量字节对查询。 */ const val LEVELS = 1 }
}

/** GAIA GATT PDU 编解码；仅四字节大端头与负载，不做流拼帧、校验和或响应成功性判断。 */
object GaiaCodec {
    /** command word 中的请求类型编号。 */
    const val COMMAND = 0
    /** command word 中的非请求通知类型编号。 */
    const val NOTIFICATION = 1
    /** command word 中的请求回复类型编号。 */
    const val RESPONSE = 2

    /**
     * 编码厂商大端字段与 (feature shl 9)|command，复制原始负载。
     * @throws IllegalArgumentException vendor 不在 0..65535，或 feature/command 不在 0..127。
     * @return 四字节头加负载；长度能否写入由 GATT 会话判断。
     */
    fun encode(command: GaiaCommand): ByteArray {
        require(command.vendor in 0..0xFFFF && command.feature in 0..127 && command.command in 0..127)
        val word = (command.feature shl 9) or command.command
        return byteArrayOf((command.vendor shr 8).toByte(), command.vendor.toByte(),
            (word shr 8).toByte(), word.toByte()) + command.payload.copyOf()
    }

    /** 头不足四字节返回 null；否则拆位并复制剩余负载，type=3 也保留，不判断匹配/拒绝。 */
    fun decode(bytes: ByteArray): GaiaPacket? {
        if (bytes.size < 4) return null
        val vendor = u16be(bytes, 0)
        val word = u16be(bytes, 2)
        return GaiaPacket(vendor, (word shr 9) and 0x7f, (word shr 7) and 0x03,
            word and 0x7f, bytes.copyOfRange(4, bytes.size))
    }

    /**
     * 解出 feature ID 集合：奇数长至少三字节且首字节 0/1 时按 continuation+ID/version 对解析；
     * 否则按每四字节大端位图解析，尾部不足四字节忽略。分页完整性由绑定另行判断。
     */
    fun features(payload: ByteArray): Set<Int> {
        if (payload.size >= 3 && payload.size % 2 == 1 && (payload[0].toInt() and 0xff) <= 1) {
            return (1 until payload.size step 2).map { payload[it].toInt() and 0xff }.toSet()
        }
        val result = mutableSetOf<Int>()
        for (offset in 0 until payload.size - 3 step 4) {
            val word = ((payload[offset].toInt() and 0xff) shl 24) or
                ((payload[offset + 1].toInt() and 0xff) shl 16) or
                ((payload[offset + 2].toInt() and 0xff) shl 8) or (payload[offset + 3].toInt() and 0xff)
            for (bit in 0..31) if ((word and (1 shl bit)) != 0) result += (offset / 4) * 32 + bit
        }
        return result
    }

    internal fun u16be(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)
}
