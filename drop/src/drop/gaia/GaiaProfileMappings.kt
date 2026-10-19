package ink.lipoly.app.sunrise.drop.gaia

import ink.lipoly.app.sunrise.drop.AncMode
import ink.lipoly.app.sunrise.drop.DropProfile
import ink.lipoly.app.sunrise.drop.GainLevel

/** GAIA numbering rules consume resolved configuration, never device names or brand tables. */
internal object GaiaProfileMappings {
    val basicModes = listOf(AncMode.OFF, AncMode.NOISE_CANCELLING, AncMode.TRANSPARENCY, AncMode.WIND)
    private val defaultAc = basicModes.withIndex().associate { it.value to it.index + 1 }
    private val defaultV2 = (basicModes + AncMode.ADAPTIVE + AncMode.LIVE)
        .withIndex().associate { it.value to it.index }
    val defaultGain = GainLevel.entries.withIndex().associate { it.value to it.index }
    /** 能力显示取可写键集合，而非以读映射推断可写模式；V1 固定两模式。 */
    fun supportedModes(path: AncPath, profile: DropProfile): Set<AncMode> = when (path) {
        AncPath.UNKNOWN -> emptySet()
        AncPath.V1 -> setOf(AncMode.OFF, AncMode.NOISE_CANCELLING)
        AncPath.AUDIO_CURATION -> (profile.audioCurationWrite ?: defaultAc).keys
        AncPath.V2 -> profile.ancV2Write?.keys ?: defaultV2.keys
    }

    /** 查写编号；无模式返回 null，由控件转为 UnsupportedCapability。编号在此不另校验范围。 */
    fun toDevice(path: AncPath, mode: AncMode, profile: DropProfile): Int? = when (path) {
        AncPath.UNKNOWN -> null
        AncPath.V1 -> when (mode) { AncMode.OFF -> 0; AncMode.NOISE_CANCELLING -> 1; else -> null }
        AncPath.AUDIO_CURATION -> (profile.audioCurationWrite ?: defaultAc)[mode]
        AncPath.V2 -> (profile.ancV2Write ?: defaultV2)[mode]
    }

    /** 优先读 map 的命中值，再逆查写 map；未知返回 null，由控件转为 Protocol。 */
    fun fromDevice(path: AncPath, value: Int, profile: DropProfile): AncMode? = when (path) {
        AncPath.UNKNOWN -> null
        AncPath.V1 -> when (value) { 0 -> AncMode.OFF; 1 -> AncMode.NOISE_CANCELLING; else -> null }
        AncPath.AUDIO_CURATION -> profile.audioCurationRead?.get(value)
            ?: (profile.audioCurationWrite ?: defaultAc).entries.firstOrNull { it.value == value }?.key
        AncPath.V2 -> profile.ancV2Read?.get(value)
            ?: (profile.ancV2Write ?: defaultV2)
                .entries.firstOrNull { it.value == value }?.key
    }

    /** 自定义 gainWrite 必须覆盖三档；缺键的 !! 不会自动回退，编号以单字节写入。 */
    fun gainToDevice(level: GainLevel, profile: DropProfile): Int = (profile.gainWrite ?: defaultGain)[level]!!
    /** 逆查实际增益编号；多个档位映到同编号时使用 map 遍历首项。 */
    fun gainFromDevice(value: Int, profile: DropProfile): GainLevel? =
        (profile.gainWrite ?: defaultGain).entries.firstOrNull { it.value == value }?.key
}
