package ink.lipoly.app.sunrise.drop

sealed interface DropProfileMatch {
    data class Address(val value: String) : DropProfileMatch
    data class NameContains(val value: String) : DropProfileMatch
}

/** Separate write/read maps are necessary: some firmware uses different numbering for each. */
data class DropProfile(
    val match: DropProfileMatch,
    val audioCurationWrite: Map<AncMode, Int>? = null,
    val audioCurationRead: Map<Int, AncMode>? = null,
    val ancV2Write: Map<AncMode, Int>? = null,
    val ancV2Read: Map<Int, AncMode>? = null,
    val gainWrite: Map<GainLevel, Int>? = null,
)

internal enum class AncPath { UNKNOWN, V1, AUDIO_CURATION, V2 }

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

    fun supportedModes(path: AncPath, profile: DropProfile): Set<AncMode> = when (path) {
        AncPath.UNKNOWN -> emptySet()
        AncPath.V1 -> setOf(AncMode.OFF, AncMode.NOISE_CANCELLING)
        AncPath.AUDIO_CURATION -> (profile.audioCurationWrite ?: defaultAc).keys
        AncPath.V2 -> profile.ancV2Write?.keys ?: defaultV2.keys
    }

    fun toDevice(path: AncPath, mode: AncMode, profile: DropProfile): Int? = when (path) {
        AncPath.UNKNOWN -> null
        AncPath.V1 -> when (mode) { AncMode.OFF -> 0; AncMode.NOISE_CANCELLING -> 1; else -> null }
        AncPath.AUDIO_CURATION -> (profile.audioCurationWrite ?: defaultAc)[mode]
        AncPath.V2 -> (profile.ancV2Write ?: defaultV2)[mode]
    }

    fun fromDevice(path: AncPath, value: Int, profile: DropProfile): AncMode? = when (path) {
        AncPath.UNKNOWN -> null
        AncPath.V1 -> when (value) { 0 -> AncMode.OFF; 1 -> AncMode.NOISE_CANCELLING; else -> null }
        AncPath.AUDIO_CURATION -> profile.audioCurationRead?.get(value)
            ?: (profile.audioCurationWrite ?: defaultAc).entries.firstOrNull { it.value == value }?.key
        AncPath.V2 -> profile.ancV2Read?.get(value)
            ?: (profile.ancV2Write ?: defaultV2)
                .entries.firstOrNull { it.value == value }?.key
    }

    fun gainToDevice(level: GainLevel, profile: DropProfile): Int = (profile.gainWrite ?: defaultGain)[level]!!
    fun gainFromDevice(value: Int, profile: DropProfile): GainLevel? =
        (profile.gainWrite ?: defaultGain).entries.firstOrNull { it.value == value }?.key
}
