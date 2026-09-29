package ink.lipoly.app.sunrise.drop

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

data class SourceStatus(
    val statusCode: Int, val currentSource: Int, val targetSource: Int,
    val transitionState: Int, val activeReason: Int,
) {
    val stableSuccess: Boolean get() = statusCode in setOf(0, 9) && transitionState == 0
}

data class SourceEntry(val sourceId: Int, val flags: Int)
data class SourceCapabilityPage(val page: Int, val totalPages: Int, val entries: List<SourceEntry>)
data class SourceCapability(
    val version: Int, val currentSource: Int, val globalFlags: Int, val entries: List<SourceEntry>,
)
data class SourceFirmwareInfo(
    val protocolMajor: Int, val protocolMinor: Int, val featureFlags: Int,
    val major: Int, val minor: Int, val patch: Int, val buildType: Int, val buildId: Long,
) {
    val features: Set<SourceFeature> get() = buildSet {
        if (featureFlags and 1 != 0) add(SourceFeature.PRESET_EQ)
        if (featureFlags and 2 != 0) add(SourceFeature.PEQ)
        if (featureFlags and 4 != 0) add(SourceFeature.AUDIO_SOURCE)
        if (featureFlags and 8 != 0) add(SourceFeature.VOLUME)
        if (featureFlags and 16 != 0) add(SourceFeature.MIC_GAIN)
    }
}
data class SourceVolume(
    val current: Int, val minimum: Int, val maximum: Int, val step: Int, val muteState: Int,
)
data class SourcePresetEq(val current: Int, val count: Int, val editablePreset: Int)
data class SourcePresetEqChange(val current: Int, val previous: Int, val editablePreset: Int)
data class SourcePeqConfig(
    val preset: Int, val pointCount: Int, val revision: Int, val preGainCentiDb: Int, val dirty: Boolean,
)
data class SourcePeqPreGain(val preset: Int, val centiDb: Int, val dirty: Boolean)
data class SourcePeqPoint(
    val index: Int, val frequencyHz: Int, val gainCentiDb: Int, val qRaw: Int, val filterId: Int,
)
data class SourcePeqCommit(val revision: Int, val activePreset: Int, val dirty: Boolean)
data class SourceMicGain(
    val currentDeciDb: Int, val minDeciDb: Int, val maxDeciDb: Int, val stepDeciDb: Int,
)

data class SourceFrame(val type: Int, val commandId: Int, val sequence: Int, val payload: ByteArray)

object SourceIds {
    const val USER_PEQ_PRESET = 7
    const val GET_AUDIO_SOURCE = 1
    const val SET_AUDIO_SOURCE = 2
    const val GET_CAPABILITY = 3
    const val GET_FW_VERSION = 4
    const val GET_VOLUME = 5
    const val SET_VOLUME = 6
    const val GET_PRESET_EQ = 7
    const val SET_PRESET_EQ = 8
    const val GET_PEQ_CONFIG = 9
    const val SET_PEQ_PREGAIN = 10
    const val GET_PEQ_POINT = 11
    const val SET_PEQ_POINT = 12
    const val COMMIT_PEQ = 13
    const val GET_MIC_GAIN = 14
    const val SET_MIC_GAIN = 15
    const val GET_COLOR = 18
    const val GET_LANGUAGE = 19
    const val GET_LEFT_SN = 20
    const val GET_RIGHT_SN = 21
    const val PING = 127
}

object SourceCodec {
    const val COMMAND = 1
    const val RESPONSE = 2
    const val NOTIFICATION = 3
    private const val HEADER = 6
    private const val MAX_PAYLOAD = 14

    fun encode(commandId: Int, sequence: Int, payload: ByteArray = byteArrayOf()): ByteArray {
        require(commandId in 0..255 && sequence in 0..255 && payload.size <= MAX_PAYLOAD)
        return byteArrayOf(0xa5.toByte(), 1, COMMAND.toByte(), commandId.toByte(),
            sequence.toByte(), payload.size.toByte()) + payload.copyOf()
    }

    fun decode(frame: ByteArray): SourceFrame? {
        if (frame.size < HEADER || (frame[0].toInt() and 0xff) != 0xa5 || frame[1].toInt() != 1) return null
        val length = frame[5].toInt() and 0xff
        if (length > MAX_PAYLOAD || frame.size < HEADER + length) return null
        val type = frame[2].toInt() and 0xff
        if (type != RESPONSE && type != NOTIFICATION) return null
        return SourceFrame(type, frame[3].toInt() and 0xff, frame[4].toInt() and 0xff,
            frame.copyOfRange(HEADER, HEADER + length))
    }

    fun status(payload: ByteArray): Int = payload.firstOrNull()?.toInt()?.and(0xff)
        ?: throw DropException.Protocol("Empty 9ECA response")

    fun sourceStatus(payload: ByteArray): SourceStatus {
        requireLength(payload, 5)
        return SourceStatus(u8(payload, 0), u8(payload, 1), u8(payload, 2), u8(payload, 3), u8(payload, 4))
    }

    fun capabilityPage(payload: ByteArray): SourceCapabilityPage {
        requireLength(payload, 4)
        val count = u8(payload, 3)
        if (count > 5) throw DropException.Protocol("Invalid 9ECA capability count")
        requireLength(payload, 4 + count * 2)
        return SourceCapabilityPage(u8(payload, 1), u8(payload, 2),
            (0 until count).map { SourceEntry(u8(payload, 4 + it * 2), u8(payload, 5 + it * 2)) })
    }

    fun sourceCapability(bytes: ByteArray): SourceCapability {
        requireLength(bytes, 4)
        val count = u8(bytes, 1)
        if (count > 8) throw DropException.Protocol("Invalid 9ECA source count")
        requireLength(bytes, 4 + count * 2)
        return SourceCapability(u8(bytes, 0), u8(bytes, 2), u8(bytes, 3),
            (0 until count).map { SourceEntry(u8(bytes, 4 + it * 2), u8(bytes, 5 + it * 2)) })
    }

    fun firmware(bytes: ByteArray, offset: Int = 0): SourceFirmwareInfo {
        requireLength(bytes, offset + 12)
        return SourceFirmwareInfo(u8(bytes, offset), u8(bytes, offset + 1), u16(bytes, offset + 2),
            u8(bytes, offset + 4), u8(bytes, offset + 5), u8(bytes, offset + 6),
            u8(bytes, offset + 7), u32(bytes, offset + 8))
    }

    fun volume(payload: ByteArray): SourceVolume {
        requireLength(payload, 6)
        return SourceVolume(u8(payload, 1), u8(payload, 2), u8(payload, 3), u8(payload, 4), u8(payload, 5))
    }

    fun presetEq(payload: ByteArray): SourcePresetEq {
        requireLength(payload, 4)
        return SourcePresetEq(u8(payload, 1), u8(payload, 2), u8(payload, 3))
    }

    fun presetChange(payload: ByteArray): SourcePresetEqChange {
        requireLength(payload, 4)
        return SourcePresetEqChange(u8(payload, 1), u8(payload, 2), u8(payload, 3))
    }

    fun peqConfig(payload: ByteArray): SourcePeqConfig {
        requireLength(payload, 8)
        return SourcePeqConfig(u8(payload, 1), u8(payload, 2), u16(payload, 3), i16(payload, 5), u8(payload, 7) != 0)
    }

    fun peqPreGain(payload: ByteArray): SourcePeqPreGain {
        requireLength(payload, 5)
        return SourcePeqPreGain(u8(payload, 1), i16(payload, 2), u8(payload, 4) != 0)
    }

    fun peqPoint(payload: ByteArray): SourcePeqPoint {
        requireLength(payload, 9)
        return SourcePeqPoint(u8(payload, 1), u16(payload, 2), i16(payload, 4), u16(payload, 6), u8(payload, 8))
    }

    fun peqCommit(payload: ByteArray): SourcePeqCommit {
        requireLength(payload, 5)
        return SourcePeqCommit(u16(payload, 1), u8(payload, 3), u8(payload, 4) != 0)
    }

    fun micGain(payload: ByteArray): SourceMicGain {
        requireLength(payload, 9)
        return SourceMicGain(i16(payload, 1), i16(payload, 3), i16(payload, 5), i16(payload, 7))
    }

    fun earbudInfo(payload: ByteArray): Int { requireLength(payload, 2); return u8(payload, 1) }

    fun snChunk(payload: ByteArray, expectedOffset: Int): ByteArray {
        requireLength(payload, 14)
        if (u8(payload, 1) != 20 || u8(payload, 2) != expectedOffset || u8(payload, 3) != 10)
            throw DropException.Protocol("Invalid 9ECA serial chunk")
        return payload.copyOfRange(4, 14)
    }

    fun u8(bytes: ByteArray, offset: Int): Int = bytes[offset].toInt() and 0xff
    fun u16(bytes: ByteArray, offset: Int): Int = u8(bytes, offset) or (u8(bytes, offset + 1) shl 8)
    fun i16(bytes: ByteArray, offset: Int): Int = u16(bytes, offset).toShort().toInt()
    fun u32(bytes: ByteArray, offset: Int): Long =
        u8(bytes, offset).toLong() or (u8(bytes, offset + 1).toLong() shl 8) or
            (u8(bytes, offset + 2).toLong() shl 16) or (u8(bytes, offset + 3).toLong() shl 24)
    fun le16(value: Int): ByteArray {
        require(value in -32768..65535)
        return byteArrayOf(value.toByte(), (value shr 8).toByte())
    }

    private fun requireLength(bytes: ByteArray, min: Int) {
        if (bytes.size < min) throw DropException.Protocol("Short 9ECA response: ${bytes.size} < $min")
    }
}
