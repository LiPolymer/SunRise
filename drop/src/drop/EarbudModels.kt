package ink.lipoly.app.sunrise.drop

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
