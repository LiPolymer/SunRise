package ink.lipoly.app.sunrise.drop

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
                GaiaPeqParameters.validateBand(band)
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
            GaiaPeqParameters.validateBand(band)
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
