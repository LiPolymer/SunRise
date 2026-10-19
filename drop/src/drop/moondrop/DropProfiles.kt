package ink.lipoly.app.sunrise.drop.moondrop

import ink.lipoly.app.sunrise.drop.AncMode
import ink.lipoly.app.sunrise.drop.DropOptions
import ink.lipoly.app.sunrise.drop.DropProfile
import ink.lipoly.app.sunrise.drop.DropProfileMatch
import ink.lipoly.app.sunrise.drop.GainLevel
import ink.lipoly.app.sunrise.drop.gaia.GaiaProfileMappings

/** 标量身份匹配与映射解析，不持有设备、不执行无线操作；内置名称表仅影响编号。 */
internal object DropProfiles {
    private val goldenAges = DropProfile(
        DropProfileMatch.NameContains("GOLDEN AGES 2"),
        audioCurationWrite = mapOf(AncMode.OFF to 1, AncMode.NOISE_CANCELLING to 2,
            AncMode.TRANSPARENCY to 4, AncMode.WIND to 3),
        audioCurationRead = GaiaProfileMappings.basicModes.withIndex().associate { it.index to it.value },
        gainWrite = mapOf(GainLevel.LOW to 2, GainLevel.MEDIUM to 1, GainLevel.HIGH to 0),
    )
    private val spaceTravel = goldenAges.copy(match = DropProfileMatch.NameContains("SPACE TRAVEL 2"))
    private val pudding = DropProfile(
        DropProfileMatch.NameContains("PUDDING"),
        ancV2Write = mapOf(AncMode.OFF to 0, AncMode.NOISE_CANCELLING to 4,
            AncMode.TRANSPARENCY to 2, AncMode.WIND to 3, AncMode.ADAPTIVE to 1),
        ancV2Read = mapOf(0 to AncMode.OFF, 1 to AncMode.ADAPTIVE, 2 to AncMode.TRANSPARENCY,
            3 to AncMode.WIND, 4 to AncMode.NOISE_CANCELLING),
        gainWrite = GaiaProfileMappings.defaultGain,
    )
    private val builtin = listOf(goldenAges, spaceTravel, pudding,
        DropProfile(DropProfileMatch.NameContains("NEKOCAKE"), gainWrite = GaiaProfileMappings.defaultGain),
        DropProfile(DropProfileMatch.NameContains("PILL"), gainWrite = GaiaProfileMappings.defaultGain),
        DropProfile(DropProfileMatch.NameContains("MOCA"), gainWrite = GaiaProfileMappings.defaultGain))

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

}
