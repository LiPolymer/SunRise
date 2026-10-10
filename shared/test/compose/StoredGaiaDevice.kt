package ink.lipoly.app.sunrise.compose

import ink.lipoly.app.sunrise.drop.AncMode
import ink.lipoly.app.sunrise.drop.AudioCodec
import ink.lipoly.app.sunrise.drop.DropException
import ink.lipoly.app.sunrise.drop.EarbudBattery
import ink.lipoly.app.sunrise.drop.GainLevel
import ink.lipoly.app.sunrise.drop.GaiaCommand
import ink.lipoly.app.sunrise.drop.GaiaControls
import ink.lipoly.app.sunrise.drop.GaiaPacket
import ink.lipoly.app.sunrise.drop.GaiaParamEqState
import ink.lipoly.app.sunrise.drop.GaiaPeqBand
import ink.lipoly.app.sunrise.drop.HeadTrackingMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/** Stateful test device: writes may apply, clamp, remain unchanged, or be canceled while delayed. */
internal class StoredGaiaDevice(initial: GaiaParamEqState, private val now: () -> Long) : GaiaControls {
    data class Write(val startedAt: Long, val bands: List<GaiaPeqBand>)

    var stored = initial.copy(bands = initial.bands.toList())
        private set
    val writes = mutableListOf<Write>()
    var reads = 0
        private set
    var available = true
    var writeDelayMillis = 0L
    var gainLimitRaw: Int? = null
    var applyWrites = true
    var nextReadFailure: Exception? = null
    var nextWriteFailure: Exception? = null
    var failAfterApply = false
    var maxConcurrentWrites = 0
        private set
    var canceledWrites = 0
        private set
    private var concurrentWrites = 0

    override suspend fun getParamEq(): GaiaParamEqState {
        reads++
        if (!available) throw DropException.UnsupportedCapability("GAIA Bluetrum PEQ")
        nextReadFailure?.let {
            nextReadFailure = null
            throw it
        }
        return stored.copy(bands = stored.bands.toList())
    }

    override suspend fun setParamEq(bands: List<GaiaPeqBand>) {
        writes += Write(now(), bands.toList())
        concurrentWrites++
        maxConcurrentWrites = maxOf(maxConcurrentWrites, concurrentWrites)
        try {
            delay(writeDelayMillis.milliseconds)
            if (!available) throw DropException.Disconnected()
            val failure = nextWriteFailure
            nextWriteFailure = null
            if (failure != null && !failAfterApply) throw failure
            val limit = gainLimitRaw
            val actualBands = bands.map { band ->
                if (limit == null) band else band.copy(gainRaw = band.gainRaw.coerceIn(-limit, limit))
            }
            if (applyWrites) stored = stored.copy(currentPreset = 63, bands = actualBands)
            if (failure != null) throw failure
        } catch (cancelled: CancellationException) {
            canceledWrites++
            throw cancelled
        } finally {
            concurrentWrites--
        }
    }

    private fun unexpected(): Nothing = throw AssertionError("Editor called an unrelated GAIA operation")
    override suspend fun getBattery(): EarbudBattery = unexpected()
    override suspend fun getAncMode(): AncMode = unexpected()
    override suspend fun setAncMode(mode: AncMode): AncMode = unexpected()
    override suspend fun getGain(): GainLevel = unexpected()
    override suspend fun setGain(level: GainLevel): GainLevel = unexpected()
    override suspend fun isLedOn(): Boolean = unexpected()
    override suspend fun setLedOn(on: Boolean): Boolean = unexpected()
    override suspend fun isSpatialOn(): Boolean = unexpected()
    override suspend fun setSpatialOn(on: Boolean): Boolean = unexpected()
    override suspend fun getHeadTracking(): HeadTrackingMode = unexpected()
    override suspend fun setHeadTracking(mode: HeadTrackingMode): HeadTrackingMode = unexpected()
    override suspend fun isCodecEnabled(codec: AudioCodec): Boolean = unexpected()
    override suspend fun setCodecEnabled(codec: AudioCodec, enabled: Boolean): Boolean = unexpected()
    override suspend fun isDynamicBassOn(): Boolean = unexpected()
    override suspend fun setDynamicBassOn(on: Boolean): Boolean = unexpected()
    override suspend fun isLeftRightReversed(): Boolean = unexpected()
    override suspend fun setLeftRightReversed(reversed: Boolean): Boolean = unexpected()
    override suspend fun getEqualizerPreset(): Int = unexpected()
    override suspend fun setEqualizerPreset(index: Int): Int = unexpected()
    override suspend fun getGestureConfiguration(gesture: Int, context: Int): GaiaPacket = unexpected()
    override suspend fun resetGestureConfiguration(): GaiaPacket = unexpected()
    override suspend fun getBasicInfo(command: Int): GaiaPacket = unexpected()
    override suspend fun getAudioCuration(command: Int): GaiaPacket = unexpected()
    override suspend fun setAudioCuration(command: Int, payload: ByteArray): GaiaPacket = unexpected()
    override suspend fun powerOff(): Unit = unexpected()
    override suspend fun requestRaw(command: GaiaCommand): GaiaPacket = unexpected()
    override suspend fun sendRaw(command: GaiaCommand): Unit = unexpected()
}
