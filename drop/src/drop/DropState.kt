package ink.lipoly.app.sunrise.drop

import ink.lipoly.app.sunrise.drop.moondrop.GaiaParamEqState
import ink.lipoly.app.sunrise.drop.moondrop.SourceFeature
import ink.lipoly.app.sunrise.drop.moondrop.SourceMicGain
import ink.lipoly.app.sunrise.drop.moondrop.SourcePresetEq
import ink.lipoly.app.sunrise.drop.moondrop.SourceStatus
import ink.lipoly.app.sunrise.drop.moondrop.SourceVolume

/**
 * 协议控制阶段，不表示蓝牙连接阶段。
 * IDLE：没有绑定；PROBING：订阅响应并探测；READY：至少存在一种可用协议；
 * ERROR：本会话初始化失败。READY 不保证 [DropCapabilities.complete] 或所有功能可用。
 */
enum class DropPhase { IDLE, PROBING, READY, ERROR }

/** 当前会话具有命令/响应特征对的协议：GAIA_BLE 为 GAIA GATT，SOURCE_9ECA 为 9ECA GATT。 */
enum class DropProtocol { GAIA_BLE, SOURCE_9ECA }

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

/**
 * 控制器当前会话的功能快照；未知标量用 null、未知 codec 用缺 key 表示，断连/关闭重置。
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
 * @property codecStates 已 GET 确认的耳机端编码选项，不表示系统正在使用的音频编码。
 * @property paramEq 完整 GAIA Bluetrum 设备读回；未加载/读回失败为 null，不存 UI 草稿。
 */
data class DropState(
    val phase: DropPhase = DropPhase.IDLE,
    val protocols: Set<DropProtocol> = emptySet(),
    val capabilities: DropCapabilities = DropCapabilities(),
    val battery: EarbudBatteryReading = EarbudBatteryReading(),
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
    val codecStates: Map<AudioCodec, Boolean> = emptyMap(),
    val paramEq: GaiaParamEqState? = null,
)
