package ink.lipoly.app.sunrise.drop

import kotlin.math.*

/**
 * Bluetrum write attenuation: official 48 kHz / 768-point log grid and negative 0.1 dB
 * rounding plus 0.1 dB margin. Uses the represented raw band values, not unavailable
 * pre-quantization Flutter inputs; this is a frequency-response estimate, not a limiter.
 */
object PeqHeadroom {
    private const val SAMPLE_COUNT = 768
    // Shared across calls and bands; no frequency/trigonometry work in the inner sampling loop.
    private val grid = DoubleArray(SAMPLE_COUNT * 4).also { values ->
        val start = log10(20.0)
        val step = (log10(20000.0) - start) / (SAMPLE_COUNT - 1)
        for (sample in 0 until SAMPLE_COUNT) {
            val hz = 10.0.pow(start + sample * step)
            val w = 2.0 * PI * hz / 48000.0
            val offset = sample * 4
            values[offset] = cos(w)
            values[offset + 1] = sin(w)
            values[offset + 2] = cos(2 * w)
            values[offset + 3] = sin(2 * w)
        }
    }

    /** Validates the entire snapshot and returns its signed16 gain header before any command. */
    fun preGainRaw(bands: List<GaiaPeqBand>): Int {
        GaiaPeqParameters.validateBands(bands)
        require(bands.all { it.filter == PeqFilter.PEAKING }) { "Bluetrum writes currently support peaking only" }
        val coefficients = Array(bands.size) { PeqBiquad.of(bands[it]) }
        var peak = Double.NEGATIVE_INFINITY
        for (sample in 0 until SAMPLE_COUNT) {
            val offset = sample * 4
            var response = 0.0
            for (coefficient in coefficients) {
                response += coefficient.responseDb(grid[offset], grid[offset + 1], grid[offset + 2], grid[offset + 3])
            }
            require(response.isFinite()) { "Cannot calculate finite EQ headroom" }
            if (response > peak) peak = response
        }
        var preGain = if (peak < 0.0) 0.0 else floor(peak * -10.0) / 10.0
        if (preGain < 0.0) preGain -= 0.1
        // Match the writer's IEEE double-to-int truncation, including values such as -8.2*60.
        val raw = (preGain * 60.0).toInt()
        require(raw in -32768..0) { "Required EQ headroom exceeds the signed16 gain header" }
        return raw
    }
}
