package ink.lipoly.app.sunrise.drop

/** 配置身份匹配，不是设备发现筛选；由控制器的 profileDevice 提供地址和名称。 */
sealed interface DropProfileMatch {
    /**
     * 地址全等匹配，忽略大小写；覆盖项中地址命中优先于名称命中。
     * @property value 配置身份地址；本层不规范化或校验 MAC 格式。
     */
    data class Address(val value: String) : DropProfileMatch
    /**
     * 非空白子串匹配，忽略大小写；不宣称同名等于同一设备。
     * @property value 名称子串，空白不匹配。
     */
    data class NameContains(val value: String) : DropProfileMatch
}

/**
 * 设备 ANC/DAC 编号映射；读写必须独立，因为固件可能对 SET/GET 使用不同编号。
 * 地址覆盖优先；选中覆盖项的 null 字段继承内置匹配配置，再由各路径使用默认映射。
 * 非 null 空 map 不等于继承。ANC 读 map 缺少某编号时仍尝试逆查写 map。
 *
 * @property match 音频/配置身份的匹配规则，不改变通讯设备。
 * @property audioCurationWrite AUDIO_CURATION 模式到写编号；默认 OFF/NC/TRANSPARENCY/WIND=1/2/3/4。
 * @property audioCurationRead AUDIO_CURATION 读编号到逻辑模式；null 或未命中时逆查写映射。
 * @property ancV2Write ANC_V2 模式到写编号；默认 OFF/NC/TRANSPARENCY/WIND/ADAPTIVE/LIVE=0..5。
 * @property ancV2Read ANC_V2 读编号到逻辑模式；null 或未命中时逆查写映射。
 * @property gainWrite DAC 档位到编号，同时供读回逆查；自定义时须含所有 GainLevel，默认 LOW/MEDIUM/HIGH=0/1/2。
 */
data class DropProfile(
    val match: DropProfileMatch,
    val audioCurationWrite: Map<AncMode, Int>? = null,
    val audioCurationRead: Map<Int, AncMode>? = null,
    val ancV2Write: Map<AncMode, Int>? = null,
    val ancV2Read: Map<Int, AncMode>? = null,
    val gainWrite: Map<GainLevel, Int>? = null,
)

/** UNKNOWN 不可控制；V1 仅 OFF/NC=0/1；其余路径使用各自独立配置。 */
internal enum class AncPath { UNKNOWN, V1, AUDIO_CURATION, V2 }

/** 标量身份匹配与映射解析，不持有设备、不执行无线操作；内置名称表仅影响编号。 */
internal object DropProfiles {
    private val basic = listOf(AncMode.OFF, AncMode.NOISE_CANCELLING, AncMode.TRANSPARENCY, AncMode.WIND)
    private val defaultAc = basic.withIndex().associate { it.value to it.index + 1 }
    private val defaultV2 = (basic + AncMode.ADAPTIVE + AncMode.LIVE)
        .withIndex().associate { it.value to it.index }
    private val defaultGain = GainLevel.entries.withIndex().associate { it.value to it.index }
    private val goldenAges = DropProfile(
        DropProfileMatch.NameContains("GOLDEN AGES 2"),
        audioCurationWrite = mapOf(AncMode.OFF to 1, AncMode.NOISE_CANCELLING to 2,
            AncMode.TRANSPARENCY to 4, AncMode.WIND to 3),
        audioCurationRead = basic.withIndex().associate { it.index to it.value },
        gainWrite = mapOf(GainLevel.LOW to 2, GainLevel.MEDIUM to 1, GainLevel.HIGH to 0),
    )
    private val spaceTravel = goldenAges.copy(match = DropProfileMatch.NameContains("SPACE TRAVEL 2"))
    private val pudding = DropProfile(
        DropProfileMatch.NameContains("PUDDING"),
        ancV2Write = mapOf(AncMode.OFF to 0, AncMode.NOISE_CANCELLING to 4,
            AncMode.TRANSPARENCY to 2, AncMode.WIND to 3, AncMode.ADAPTIVE to 1),
        ancV2Read = mapOf(0 to AncMode.OFF, 1 to AncMode.ADAPTIVE, 2 to AncMode.TRANSPARENCY,
            3 to AncMode.WIND, 4 to AncMode.NOISE_CANCELLING),
        gainWrite = defaultGain,
    )
    private val builtin = listOf(goldenAges, spaceTravel, pudding,
        DropProfile(DropProfileMatch.NameContains("NEKOCAKE"), gainWrite = defaultGain),
        DropProfile(DropProfileMatch.NameContains("PILL"), gainWrite = defaultGain),
        DropProfile(DropProfileMatch.NameContains("MOCA"), gainWrite = defaultGain))

    /**
     * 先找内置首个匹配，再选覆盖地址首项，最后覆盖任意首项。
     * 合并仅填充覆盖项的 null 字段，不合并 map 条目；无匹配返回全部缺省的配置。
     */
    fun resolve(options: DropOptions, address: String?, name: String?): DropProfile {
        val overrides = options.profileOverrides
        val builtinMatch = builtin.firstOrNull { matches(it.match, address, name) }
        val override = overrides.firstOrNull { it.match is DropProfileMatch.Address && matches(it.match, address, name) }
            ?: overrides.firstOrNull { matches(it.match, address, name) }
        if (override == null) return builtinMatch ?: DropProfile(DropProfileMatch.NameContains(""))
        return override.copy(
            audioCurationWrite = override.audioCurationWrite ?: builtinMatch?.audioCurationWrite,
            audioCurationRead = override.audioCurationRead ?: builtinMatch?.audioCurationRead,
            ancV2Write = override.ancV2Write ?: builtinMatch?.ancV2Write,
            ancV2Read = override.ancV2Read ?: builtinMatch?.ancV2Read,
            gainWrite = override.gainWrite ?: builtinMatch?.gainWrite,
        )
    }

    private fun matches(match: DropProfileMatch, address: String?, name: String?): Boolean = when (match) {
        is DropProfileMatch.Address -> address?.equals(match.value, ignoreCase = true) == true
        is DropProfileMatch.NameContains -> match.value.isNotBlank() &&
            name?.contains(match.value, ignoreCase = true) == true
    }

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
