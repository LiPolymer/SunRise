package ink.lipoly.app.sunrise.headset

import ink.lipoly.app.sunrise.blueConnector.BtDevice
import ink.lipoly.app.sunrise.blueConnector.BtException
import ink.lipoly.app.sunrise.blueConnector.GattCharacteristic
import ink.lipoly.app.sunrise.blueConnector.GattEvent
import ink.lipoly.app.sunrise.blueConnector.GattProperty
import ink.lipoly.app.sunrise.blueConnector.GattService
import ink.lipoly.app.sunrise.blueConnector.GattSession
import ink.lipoly.app.sunrise.drop.AudioCodec
import ink.lipoly.app.sunrise.drop.GaiaCodec
import ink.lipoly.app.sunrise.drop.GaiaCommand
import ink.lipoly.app.sunrise.drop.GaiaIds
import ink.lipoly.app.sunrise.drop.GaiaPacket
import ink.lipoly.app.sunrise.drop.GaiaPeqBand
import ink.lipoly.app.sunrise.drop.PeqFilter
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/** Read-only device storage exposed through public GATT/GAIA bytes, not library test internals. */
internal class HeadsetGattFixture(
    override val device: BtDevice,
    count: Int = 5,
    negotiatedMtu: Int = 247,
    override val id: Long = 1,
) : GattSession {
    private companion object {
        const val GAIA_SERVICE = "00001100-d102-11e1-9b23-00025b00a5a5"
        const val GAIA_COMMAND = "00001101-d102-11e1-9b23-00025b00a5a5"
        const val GAIA_RESPONSE = "00001102-d102-11e1-9b23-00025b00a5a5"
    }

    private class Characteristic(
        override val uuid: String,
        override val properties: Set<GattProperty>,
    ) : GattCharacteristic {
        override val serviceUuid = GAIA_SERVICE
    }

    private val commandCharacteristic = Characteristic(GAIA_COMMAND, setOf(GattProperty.WRITE))
    private val responseCharacteristic = Characteristic(GAIA_RESPONSE, setOf(GattProperty.NOTIFY))
    override val services = listOf(GattService(GAIA_SERVICE, listOf(commandCharacteristic, responseCharacteristic)))
    override val mtu = MutableStateFlow(negotiatedMtu)
    override val events = MutableSharedFlow<GattEvent>()
    private var closed = false
    private var notifications = false

    var present = true
    var presets = listOf(0, 2, 63)
    var currentPreset = 2
    var totalGainRaw = -17
    var bands = List(count) { GaiaPeqBand(it, 1000 + it * 100, 0, 4096, PeqFilter.PEAKING) }
    val codecStorage = mutableMapOf(AudioCodec.LC3 to false, AudioCodec.LDAC to false, AudioCodec.LHDC to false)
    val commands = mutableListOf<GaiaPacket>()

    override suspend fun read(characteristic: GattCharacteristic): ByteArray {
        ensureOpen()
        if (characteristic !== commandCharacteristic && characteristic !== responseCharacteristic) {
            throw BtException.InvalidGattHandle()
        }
        throw BtException.UnsupportedOperation("Fixture characteristics are write/notify only")
    }

    override suspend fun setNotifications(characteristic: GattCharacteristic, enabled: Boolean) {
        ensureOpen()
        if (characteristic !== responseCharacteristic) throw BtException.InvalidGattHandle()
        notifications = enabled
    }

    override suspend fun requestMtu(value: Int): Int {
        ensureOpen()
        return mtu.value // Preserve the device's negotiated value rather than echoing the request.
    }

    override fun close() {
        closed = true
        notifications = false
    }

    override suspend fun write(characteristic: GattCharacteristic, value: ByteArray) {
        ensureOpen()
        if (characteristic !== commandCharacteristic) throw BtException.InvalidGattHandle()
        if (value.size > mtu.value - 3) throw BtException.PacketTooLarge()
        val packet = GaiaCodec.decode(value) ?: error("Invalid test command")
        check(packet.type == GaiaCodec.COMMAND)
        commands += packet
        val payload = when (packet.feature) {
            GaiaIds.BASIC -> when (packet.command) {
                GaiaIds.Basic.FEATURES -> byteArrayOf(0, GaiaIds.MUSIC_PROCESSING.toByte(), 1,
                    GaiaIds.CODEC_TYPE.toByte(), 1, GaiaIds.ANC_V2.toByte(), 1)
                else -> error("Unexpected basic command ${packet.command}")
            }
            GaiaIds.CODEC_TYPE -> {
                val codec = when (packet.command) {
                    GaiaIds.Codec.GET_LC3 -> AudioCodec.LC3
                    GaiaIds.Codec.GET_LDAC -> AudioCodec.LDAC
                    GaiaIds.Codec.GET_LHDC -> AudioCodec.LHDC
                    else -> error("Only codec queries are supported by the headset fixture")
                }
                byteArrayOf(if (codecStorage.getValue(codec)) 1 else 0)
            }
            GaiaIds.ANC_V2 -> when (packet.command) {
                GaiaIds.Anc.GET_MODE -> byteArrayOf(0)
                else -> error("Only ANC queries are supported by the headset fixture")
            }
            GaiaIds.MUSIC_PROCESSING -> eqCommand(packet)
            else -> error("Unexpected feature ${packet.feature}")
        }
        if (notifications) {
            val bytes = GaiaCodec.encode(GaiaCommand(packet.feature, packet.command, payload, packet.vendor))
            // RESPONSE is bit 8 of the command word, the low bit of its high byte.
            bytes[2] = (bytes[2].toInt() or 1).toByte()
            events.emit(GattEvent.ValueChanged(responseCharacteristic, bytes))
        }
    }

    private fun eqCommand(packet: GaiaPacket): ByteArray = when (packet.command) {
        GaiaIds.Eq.GET_STATE -> byteArrayOf(if (present) 1 else 0)
        GaiaIds.Eq.GET_PRESETS -> ByteArray(1 + presets.size).also { result ->
            result[0] = presets.size.toByte()
            presets.forEachIndexed { index, preset -> result[index + 1] = preset.toByte() }
        }
        GaiaIds.Eq.GET_PRESET -> byteArrayOf(currentPreset.toByte())
        GaiaIds.Eq.GET_BAND_COUNT -> byteArrayOf(bands.size.toByte())
        GaiaIds.Eq.GET_USER_CONFIG -> {
            val start = packet.payload[0].toInt() and 0xff
            val end = packet.payload[1].toInt() and 0xff
            configurationBytes(start..end)
        }
        GaiaIds.Eq.SET_USER_CONFIG -> error("Selection/reference scenarios must never send an EQ configuration SET")
        else -> error("Unexpected EQ command ${packet.command}; headset fixture is read-only")
    }

    /** Independent device-side big-endian fields; never use the production PEQ payload codec. */
    private fun configurationBytes(range: IntRange): ByteArray {
        val result = ByteArray(4 + 7 * range.count())
        result[0] = range.first.toByte()
        result[1] = range.last.toByte()
        put16(result, 2, totalGainRaw)
        for (index in range) {
            val offset = 4 + (index - range.first) * 7
            val band = bands[index]
            put16(result, offset, band.frequencyHz)
            put16(result, offset + 2, band.qRaw)
            result[offset + 4] = if (band.filter == PeqFilter.PEAKING) 0 else band.filter.gaiaId.toByte()
            put16(result, offset + 5, band.gainRaw)
        }
        return result
    }

    private fun put16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value shr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }

    private fun ensureOpen() {
        if (closed) throw BtException.Disconnected()
    }
}
