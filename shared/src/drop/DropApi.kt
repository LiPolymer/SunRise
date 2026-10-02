package ink.lipoly.app.sunrise.drop

enum class DropPhase { IDLE, PROBING, READY, ERROR }

enum class DropProtocol { GAIA_BLE, SOURCE_9ECA }
enum class AncMode { OFF, NOISE_CANCELLING, TRANSPARENCY, WIND, ADAPTIVE, LIVE }
enum class GainLevel { LOW, MEDIUM, HIGH }
enum class HeadTrackingMode { OFF, THIRTY_DEGREES, SURROUND }
enum class AudioCodec { LC3, LDAC, LHDC }
enum class EarbudSide { LEFT, RIGHT }

data class EarbudBattery(val left: Int? = null, val right: Int? = null, val case: Int? = null)

data class DropCapabilities(
    val gaiaFeatures: Set<Int> = emptySet(),
    val ancModes: Set<AncMode> = emptySet(),
    val sourceFeatures: Set<SourceFeature> = emptySet(),
    val complete: Boolean = false,
)

enum class SourceFeature { AUDIO_SOURCE, VOLUME, PRESET_EQ, PEQ, MIC_GAIN }

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

sealed interface DropEvent {
    data class SourceNotification(val commandId: Int, val payload: ByteArray) : DropEvent
    data class GaiaNotification(val packet: GaiaPacket) : DropEvent
    data class Error(val cause: DropException) : DropEvent
}

sealed class DropException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class UnsupportedDevice : DropException("Device does not expose GAIA or 9ECA")
    class UnsupportedCapability(val capability: String) : DropException("Unsupported capability: $capability")
    class NotReady : DropException("Earbud protocol is not ready")
    class Timeout(val operation: String) : DropException("Timed out: $operation")
    class Disconnected : DropException("Earbud disconnected")
    class Unverified(val operation: String, cause: Throwable? = null) :
        DropException("$operation command was sent, but readback could not be verified", cause)
    class AncModeMismatch(val requested: AncMode, val observed: AncMode) :
        DropException("ANC mode readback mismatch: requested $requested, observed $observed")
    class Protocol(message: String) : DropException(message)
    class Rejected(val status: Int, operation: String) : DropException("$operation rejected with status $status")
    class Transport(message: String, cause: Throwable? = null) : DropException(message, cause)
}

data class DropOptions(val profileOverrides: List<DropProfile> = emptyList())

interface GaiaControls {
    suspend fun getBattery(): EarbudBattery
    suspend fun getAncMode(): AncMode
    suspend fun setAncMode(mode: AncMode): AncMode
    suspend fun getGain(): GainLevel
    suspend fun setGain(level: GainLevel): GainLevel
    suspend fun isLedOn(): Boolean
    suspend fun setLedOn(on: Boolean): Boolean
    suspend fun isSpatialOn(): Boolean
    suspend fun setSpatialOn(on: Boolean): Boolean
    suspend fun getHeadTracking(): HeadTrackingMode
    suspend fun setHeadTracking(mode: HeadTrackingMode): HeadTrackingMode
    suspend fun isCodecEnabled(codec: AudioCodec): Boolean
    suspend fun setCodecEnabled(codec: AudioCodec, enabled: Boolean): Boolean
    suspend fun isDynamicBassOn(): Boolean
    suspend fun setDynamicBassOn(on: Boolean): Boolean
    suspend fun isLeftRightReversed(): Boolean
    suspend fun setLeftRightReversed(reversed: Boolean): Boolean
    suspend fun getEqualizerPreset(): Int
    suspend fun setEqualizerPreset(index: Int): Int
    suspend fun getGestureConfiguration(gesture: Int, context: Int): GaiaPacket
    suspend fun resetGestureConfiguration(): GaiaPacket
    suspend fun getBasicInfo(command: Int): GaiaPacket
    suspend fun getAudioCuration(command: Int): GaiaPacket
    suspend fun setAudioCuration(command: Int, payload: ByteArray): GaiaPacket
    suspend fun powerOff()
    /** Raw requests require a matching response; raw sends complete after the transport write. */
    suspend fun requestRaw(command: GaiaCommand): GaiaPacket
    suspend fun sendRaw(command: GaiaCommand)
}

interface SourceControls {
    suspend fun getAudioSource(): SourceStatus
    suspend fun setAudioSource(sourceId: Int, options: SourceSwitchOptions = SourceSwitchOptions()): SourceStatus
    suspend fun getCapabilityPage(page: Int): SourceCapabilityPage
    suspend fun readSourceCapability(): SourceCapability
    suspend fun getFirmwareInfo(): SourceFirmwareInfo
    suspend fun getVolume(): SourceVolume
    suspend fun setVolume(mode: Int, value: Int, flags: Int = 0): SourceVolume
    suspend fun getPresetEq(): SourcePresetEq
    suspend fun setPresetEq(index: Int): SourcePresetEqChange
    suspend fun getPeqConfig(): SourcePeqConfig
    suspend fun setPeqPreGain(centiDb: Int): SourcePeqPreGain
    suspend fun getPeqPoint(index: Int): SourcePeqPoint
    suspend fun setPeqPoint(point: SourcePeqPoint): SourcePeqPoint
    suspend fun commitPeq(action: Int, revision: Int): SourcePeqCommit
    suspend fun getMicGain(): SourceMicGain
    suspend fun setMicGain(deciDb: Int): SourceMicGain
    suspend fun getEarbudColor(): Int
    suspend fun getEarbudLanguage(): Int
    suspend fun getEarbudSerial(side: EarbudSide): ByteArray
    suspend fun ping()
}
