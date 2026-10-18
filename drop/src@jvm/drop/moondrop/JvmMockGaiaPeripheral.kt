package ink.lipoly.app.sunrise.drop.moondrop

import ink.lipoly.app.sunrise.bluetooth.BtException
import ink.lipoly.app.sunrise.drop.DropException
import ink.lipoly.app.sunrise.drop.HeadTrackingMode
import ink.lipoly.app.sunrise.drop.gaia.GaiaCodec
import ink.lipoly.app.sunrise.drop.gaia.GaiaCommand
import ink.lipoly.app.sunrise.drop.gaia.GaiaPacket

/**
 * In-memory GAIA simulation for the desktop UI, not a Bluetooth device or a firmware emulator.
 * One instance belongs to one manager's device and survives replacement of its GATT sessions.
 * Only implemented features are advertised; EQ storage is volatile and makes no DSP/Flash claims.
 * GATT handles and notification delivery belong to the transport, not this peripheral.
 */
internal class JvmMockGaiaPeripheral {
    private var ancMode = 0
    private var gain = 0
    private var ledOn = 1
    private var spatialOn = 0
    private var headTracking = 0
    private val codecStates = intArrayOf(1, 1, 0)
    private var preset = 0
    private var totalGainRaw = 0
    private val bands = arrayOf(
        GaiaPeqBand(0, 80, 0, 4096, PeqFilter.PEAKING),
        GaiaPeqBand(1, 250, 0, 4096, PeqFilter.PEAKING),
        GaiaPeqBand(2, 1000, 0, 4096, PeqFilter.PEAKING),
        GaiaPeqBand(3, 4000, 0, 4096, PeqFilter.PEAKING),
        GaiaPeqBand(4, 12000, 0, 4096, PeqFilter.PEAKING),
    )
    private val bandView = bands.asList()

    /** Decodes a command, updates simulated storage and returns its response, or null for one-way SET. */
    @Synchronized
    fun handle(value: ByteArray): ByteArray? {
        val packet = GaiaCodec.decode(value)
            ?: throw BtException.Transport("Truncated simulated GAIA command")
        if (packet.vendor != VENDOR || packet.type != GaiaCodec.COMMAND) unsupported(packet)
        val payload = when (packet.feature) {
            GaiaIds.BASIC -> basic(packet)
            GaiaIds.BATTERY -> {
                if (packet.command != GaiaIds.Battery.LEVELS) unsupported(packet)
                noPayload(packet)
                BATTERY_LEVELS
            }
            GaiaIds.ANC_V2 -> when (packet.command) {
                GaiaIds.Anc.GET_MODE -> get(packet, ancMode)
                GaiaIds.Anc.SET_MODE -> {
                    ancMode = setting(packet, 0..5)
                    null
                }
                else -> unsupported(packet)
            }
            GaiaIds.DAC_GAIN -> when (packet.command) {
                GaiaIds.Device.GET -> get(packet, gain)
                GaiaIds.Device.SET -> {
                    gain = setting(packet, 0..2)
                    byteArrayOf(gain.toByte())
                }
                else -> unsupported(packet)
            }
            GaiaIds.LED -> when (packet.command) {
                GaiaIds.Device.GET -> get(packet, ledOn)
                GaiaIds.Device.SET -> {
                    ledOn = setting(packet, 0..1)
                    byteArrayOf(ledOn.toByte())
                }
                else -> unsupported(packet)
            }
            GaiaIds.SPATIAL_AUDIO -> spatial(packet)
            GaiaIds.CODEC_TYPE -> codec(packet)
            GaiaIds.MUSIC_PROCESSING -> equalizer(packet)
            else -> unsupported(packet)
        } ?: return null
        return GaiaCodec.encode(GaiaCommand(packet.feature, packet.command, payload, packet.vendor)).also {
            // RESPONSE is bit 8 of the command word, i.e. bit 0 of the high byte.
            it[2] = (it[2].toInt() or ((GaiaCodec.RESPONSE shl 7) shr 8)).toByte()
        }
    }

    private fun basic(packet: GaiaPacket): ByteArray {
        val result = when (packet.command) {
            GaiaIds.Basic.FEATURES -> FEATURES
            GaiaIds.Basic.VERSION, GaiaIds.Basic.APP_VERSION -> MOCK_VERSION
            GaiaIds.Basic.SERIAL, GaiaIds.Basic.LEFT_SN, GaiaIds.Basic.RIGHT_SN -> MOCK_SERIAL
            GaiaIds.Basic.VARIANT -> MOCK_VARIANT
            GaiaIds.Basic.COLOR, GaiaIds.Basic.LANGUAGE, GaiaIds.Basic.TWS_STATUS -> MOCK_INFO
            else -> unsupported(packet)
        }
        noPayload(packet)
        return result
    }

    private fun spatial(packet: GaiaPacket): ByteArray = when (packet.command) {
        GaiaIds.Spatial.GET -> get(packet, spatialOn)
        GaiaIds.Spatial.SET -> {
            spatialOn = setting(packet, 0..1)
            byteArrayOf(spatialOn.toByte())
        }
        GaiaIds.Spatial.GET_TRACKING -> get(packet, headTracking)
        GaiaIds.Spatial.SET_TRACKING -> {
            headTracking = setting(packet, HeadTrackingMode.entries.indices)
            byteArrayOf(headTracking.toByte())
        }
        else -> unsupported(packet)
    }

    private fun codec(packet: GaiaPacket): ByteArray? {
        val index = when (packet.command) {
            GaiaIds.Codec.GET_LC3, GaiaIds.Codec.SET_LC3 -> 0
            GaiaIds.Codec.GET_LDAC, GaiaIds.Codec.SET_LDAC -> 1
            GaiaIds.Codec.GET_LHDC, GaiaIds.Codec.SET_LHDC -> 2
            else -> unsupported(packet)
        }
        return when (packet.command) {
            GaiaIds.Codec.SET_LC3, GaiaIds.Codec.SET_LDAC, GaiaIds.Codec.SET_LHDC -> {
                codecStates[index] = setting(packet, 0..1)
                null
            }
            else -> get(packet, codecStates[index])
        }
    }

    private fun equalizer(packet: GaiaPacket): ByteArray? = when (packet.command) {
        GaiaIds.Eq.GET_STATE -> get(packet, 1)
        GaiaIds.Eq.GET_PRESETS -> {
            noPayload(packet)
            PRESETS
        }
        GaiaIds.Eq.GET_PRESET -> get(packet, preset)
        GaiaIds.Eq.SET_PRESET -> {
            val requested = setting(packet, 0..255)
            if (requested != 0 && requested != GaiaBluetrumPeqCodec.USER_PRESET) unsupported(packet)
            preset = requested
            byteArrayOf(preset.toByte())
        }
        GaiaIds.Eq.GET_BAND_COUNT -> get(packet, bands.size)
        GaiaIds.Eq.GET_USER_CONFIG -> {
            if (packet.payload.size != 2) malformed(packet, "Expected start/end band indices")
            val range = bandRange(packet)
            GaiaBluetrumPeqCodec.encode(bandView.subList(range.first, range.last + 1), totalGainRaw)
        }
        GaiaIds.Eq.SET_USER_CONFIG -> {
            if (packet.payload.size < 4) malformed(packet, "Truncated Bluetrum configuration")
            val range = bandRange(packet)
            val configuration = try {
                GaiaBluetrumPeqCodec.decode(packet.payload, range)
            } catch (e: DropException.UnsupportedCapability) {
                throw BtException.UnsupportedOperation("Simulated GAIA EQ filter: ${e.message}")
            } catch (e: DropException.Protocol) {
                throw BtException.Transport("Invalid simulated GAIA EQ configuration", e)
            }
            if (configuration.bands.any { it.filter != PeqFilter.PEAKING }) unsupported(packet)
            // Decode and validate the entire chunk before mutating any part of the stored snapshot.
            configuration.bands.forEach { bands[it.index] = it }
            totalGainRaw = configuration.totalGainRaw
            null
        }
        else -> unsupported(packet) // In particular, no STORE_USER_CONFIG or SET_NV_ID/Flash support.
    }

    private fun bandRange(packet: GaiaPacket): IntRange {
        val start = packet.payload[0].toInt() and 0xff
        val end = packet.payload[1].toInt() and 0xff
        if (start > end || end >= bands.size) malformed(packet, "Invalid band range $start..$end")
        return start..end
    }

    private fun get(packet: GaiaPacket, value: Int): ByteArray {
        noPayload(packet)
        return byteArrayOf(value.toByte())
    }

    private fun setting(packet: GaiaPacket, allowed: IntRange): Int {
        if (packet.payload.size != 1) malformed(packet, "Expected one setting byte")
        val value = packet.payload[0].toInt() and 0xff
        if (value !in allowed) malformed(packet, "Setting $value is outside $allowed")
        return value
    }

    private fun noPayload(packet: GaiaPacket) {
        if (packet.payload.isNotEmpty()) malformed(packet, "Expected an empty request payload")
    }

    private fun malformed(packet: GaiaPacket, reason: String): Nothing =
        throw BtException.Transport("Invalid simulated GAIA ${packet.feature}/${packet.command}: $reason")

    private fun unsupported(packet: GaiaPacket): Nothing =
        throw BtException.UnsupportedOperation("Simulated GAIA ${packet.feature}/${packet.command}")

    private companion object {
        const val VENDOR = 0x001D
        val BATTERY_LEVELS = byteArrayOf(1, 82, 2, 76, 3, 64)
        val PRESETS = byteArrayOf(2, 0, GaiaBluetrumPeqCodec.USER_PRESET.toByte())
        // A complete single feature page: continuation=0, followed by implemented feature/version pairs.
        val FEATURES = byteArrayOf(
            0,
            GaiaIds.BASIC.toByte(), 1,
            GaiaIds.BATTERY.toByte(), 1,
            GaiaIds.ANC_V2.toByte(), 1,
            GaiaIds.DAC_GAIN.toByte(), 1,
            GaiaIds.CODEC_TYPE.toByte(), 1,
            GaiaIds.LED.toByte(), 1,
            GaiaIds.SPATIAL_AUDIO.toByte(), 1,
            GaiaIds.MUSIC_PROCESSING.toByte(), 1,
        )
        val MOCK_VERSION = "SunRise JVM simulation".encodeToByteArray()
        val MOCK_SERIAL = "SIMULATED-NO-HARDWARE-SERIAL".encodeToByteArray()
        val MOCK_VARIANT = "Simulated MOONDROP MOCA".encodeToByteArray()
        val MOCK_INFO = "SIMULATED".encodeToByteArray()
    }
}
