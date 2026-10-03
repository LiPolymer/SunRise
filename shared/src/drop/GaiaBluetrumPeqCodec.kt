package ink.lipoly.app.sunrise.drop

import kotlin.math.roundToInt

/** Only the confirmed GAIA Bluetrum layout; no 9ECA or Flash-save assumptions. */
internal object GaiaBluetrumPeqCodec {
    const val USER_PRESET = 63

    fun byteValue(payload: ByteArray, operation: String): Int =
        payload.firstOrNull()?.toInt()?.and(0xff)
            ?: throw DropException.Protocol("Empty $operation response")

    fun boolean(payload: ByteArray, operation: String): Boolean = when (byteValue(payload, operation)) {
        0 -> false
        1 -> true
        else -> throw DropException.Protocol("Invalid $operation boolean")
    }

    fun presets(payload: ByteArray): List<Int> {
        val count = byteValue(payload, "EQ presets")
        if (payload.size != count + 1) throw DropException.Protocol("Invalid EQ preset count/length")
        val result = List(count) { payload[it + 1].toInt() and 0xff }
        if (result.toSet().size != count) throw DropException.Protocol("Duplicate EQ preset IDs")
        return result
    }

    fun frequencyHz(value: Double): Int {
        require(value.isFinite() && value in 20.0..20000.0) { "Frequency must be 20..20000 Hz" }
        return value.roundToInt()
    }

    fun gainRaw(value: Double): Int {
        require(value.isFinite() && value in (-32768 / 60.0)..(32767 / 60.0)) { "Gain exceeds s16 units" }
        return (value * 60.0).toInt()
    }

    fun qRaw(value: Double): Int {
        require(value.isFinite() && value in (1 / 4096.0)..(65535 / 4096.0)) { "Q exceeds u16 units" }
        return (value * 4096.0).toInt()
    }

    fun validateBand(band: GaiaPeqBand) {
        require(band.index in 0..254) { "Invalid band index" }
        require(band.gainRaw in -32768..32767) { "Gain exceeds s16 units" }
        if (band.filter == PeqFilter.BYPASS) {
            require(band.frequencyHz in 0..65535 && band.qRaw in 0..65535) { "Invalid bypass raw fields" }
        } else {
            require(band.frequencyHz in 20..20000 && band.qRaw in 1..65535) { "Invalid enabled band parameters" }
        }
    }

    fun validateBands(bands: List<GaiaPeqBand>) {
        require(bands.size in 1..255) { "A complete EQ snapshot must contain 1..255 bands" }
        bands.forEachIndexed { index, band ->
            require(band.index == index) { "Bands must have contiguous device indices" }
            validateBand(band)
        }
    }

    data class Configuration(val totalGainRaw: Int, val bands: List<GaiaPeqBand>)

    fun decode(payload: ByteArray, expectedRange: IntRange): Configuration {
        if (payload.size < 4) throw DropException.Protocol("Truncated Bluetrum configuration")
        val start = payload[0].toInt() and 0xff
        val end = payload[1].toInt() and 0xff
        if (end < start || start != expectedRange.first || end != expectedRange.last || end > 254)
            throw DropException.Protocol("Unexpected Bluetrum band range $start..$end")
        val count = end - start + 1
        if (count > 7 || payload.size != 4 + 7 * count)
            throw DropException.Protocol("Invalid Bluetrum configuration length")
        val bands = List(count) { position ->
            val offset = 4 + 7 * position
            val filterId = payload[offset + 4].toInt() and 0xff
            // Official Bluetrum bulk writes use 0 for peaking, not GAIA's BYPASS=0.
            val filter = if (filterId == 0) PeqFilter.PEAKING else
                PeqFilter.entries.firstOrNull { it.gaiaId == filterId }
                    ?: throw DropException.UnsupportedCapability("Bluetrum filter $filterId")
            val band = GaiaPeqBand(start + position, GaiaCodec.u16be(payload, offset),
                signed16(payload, offset + 5), GaiaCodec.u16be(payload, offset + 2), filter)
            try {
                validateBand(band)
            } catch (e: IllegalArgumentException) {
                throw DropException.Protocol(e.message ?: "Invalid Bluetrum band")
            }
            band
        }
        return Configuration(signed16(payload, 2), bands)
    }

    fun encode(bands: List<GaiaPeqBand>, totalGainRaw: Int): ByteArray {
        require(bands.size in 1..7 && totalGainRaw in -32768..32767)
        bands.forEachIndexed { position, band ->
            validateBand(band)
            require(band.filter == PeqFilter.PEAKING) { "Bluetrum writes currently support peaking only" }
            require(band.index == bands.first().index + position)
        }
        val payload = ByteArray(4 + 7 * bands.size)
        payload[0] = bands.first().index.toByte()
        payload[1] = bands.last().index.toByte()
        put16(payload, 2, totalGainRaw)
        bands.forEachIndexed { position, band ->
            val offset = 4 + 7 * position
            put16(payload, offset, band.frequencyHz)
            put16(payload, offset + 2, band.qRaw)
            payload[offset + 4] = 0
            put16(payload, offset + 5, band.gainRaw)
        }
        return payload
    }

    fun batchSize(maxWriteSize: Int): Int {
        val count = minOf(7, (maxWriteSize - 8) / 7)
        if (count < 1) throw DropException.Protocol("ATT MTU cannot fit one Bluetrum band")
        return count
    }

    private fun signed16(payload: ByteArray, offset: Int): Int = GaiaCodec.u16be(payload, offset).toShort().toInt()
    private fun put16(payload: ByteArray, offset: Int, value: Int) {
        payload[offset] = (value shr 8).toByte()
        payload[offset + 1] = value.toByte()
    }
}
