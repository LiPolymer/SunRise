package ink.lipoly.app.sunrise.drop

import kotlin.math.roundToInt

/** Raw-unit conversion and complete-snapshot validation, without protocol I/O. */
object GaiaPeqParameters {
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
}
