package ink.lipoly.app.sunrise.catalog

import ink.lipoly.app.sunrise.drop.GaiaBluetrumPeqCodec
import ink.lipoly.app.sunrise.drop.GaiaPeqBand
import ink.lipoly.app.sunrise.drop.PeqBiquad
import ink.lipoly.app.sunrise.drop.PeqFilter
import ink.lipoly.app.sunrise.drop.PeqHeadroom
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

internal class AcousticOverlay(
    val frequencyHz: DoubleArray,
    val referenceDb: DoubleArray,
    val predictedDb: DoubleArray?,
    val normalizationHz: Double?,
    val includedPreGainRaw: Int?,
    val predictionError: String? = null,
) {
    val scale: AcousticScale? = acousticScale(this)
}

/** Immutable trigonometry shared by every draft on the catalogue's display grid. */
private val acousticGridTrig = DoubleArray(catalogFrequencyGrid.size * 4).also { values ->
    for (index in catalogFrequencyGrid.indices) {
        val w = 2.0 * PI * catalogFrequencyGrid[index] / 48000.0
        val offset = index * 4
        values[offset] = cos(w)
        values[offset + 1] = sin(w)
        values[offset + 2] = cos(2.0 * w)
        values[offset + 3] = sin(2.0 * w)
    }
}

/** Model data plus the writable draft's 48 kHz RBJ response, not a measurement. */
internal fun buildAcousticOverlay(
    reference: FrequencyResponse,
    bands: List<GaiaPeqBand>?,
    includePreGain: Boolean,
): AcousticOverlay {
    val sampled = sampleCatalogResponse(reference)
    fun referenceOnly(error: String? = null) = AcousticOverlay(
        sampled.frequencyHz, sampled.referenceDb, null, sampled.normalizationHz, null, error,
    )
    if (sampled.frequencyHz.isEmpty()) return referenceOnly("Reference has no displayable frequency range (20–20000 Hz)")
    if (bands.isNullOrEmpty()) return referenceOnly()
    try {
        GaiaBluetrumPeqCodec.validateBands(bands)
        require(bands.all { it.filter == PeqFilter.PEAKING }) { "Bluetrum writes currently support peaking only" }
    } catch (error: IllegalArgumentException) {
        return referenceOnly("Draft is not writable: ${error.message}")
    }
    val preGainRaw = if (includePreGain) {
        try {
            PeqHeadroom.preGainRaw(bands)
        } catch (error: IllegalArgumentException) {
            return referenceOnly("Cannot calculate prediction pre-gain: ${error.message}")
        }
    } else null
    val preGainDb = (preGainRaw ?: 0) / 60.0
    val coefficients = Array(bands.size) { PeqBiquad.of(bands[it]) }
    val predicted = DoubleArray(sampled.frequencyHz.size)
    var gridIndex = 0
    for (index in sampled.frequencyHz.indices) {
        val frequency = sampled.frequencyHz[index]
        while (gridIndex < catalogFrequencyGrid.lastIndex && catalogFrequencyGrid[gridIndex] < frequency) gridIndex++
        val c1: Double
        val s1: Double
        val c2: Double
        val s2: Double
        if (catalogFrequencyGrid[gridIndex] == frequency) {
            val offset = gridIndex * 4
            c1 = acousticGridTrig[offset]
            s1 = acousticGridTrig[offset + 1]
            c2 = acousticGridTrig[offset + 2]
            s2 = acousticGridTrig[offset + 3]
        } else {
            // Only the measured intersection endpoints can fall outside the shared grid.
            val w = 2.0 * PI * frequency / 48000.0
            c1 = cos(w)
            s1 = sin(w)
            c2 = cos(2.0 * w)
            s2 = sin(2.0 * w)
        }
        var eqDb = 0.0
        for (bandIndex in coefficients.indices) {
            val responseDb = coefficients[bandIndex].responseDb(c1, s1, c2, s2)
            if (!responseDb.isFinite()) return referenceOnly("Nonfinite EQ response for band ${bands[bandIndex].index} at $frequency Hz")
            eqDb += responseDb
        }
        val predictionDb = sampled.referenceDb[index] + eqDb + preGainDb
        if (!predictionDb.isFinite()) return referenceOnly("Nonfinite predicted response at $frequency Hz")
        predicted[index] = predictionDb
    }
    return AcousticOverlay(sampled.frequencyHz, sampled.referenceDb, predicted, sampled.normalizationHz, preGainRaw)
}

internal data class AcousticScale(val minDb: Double, val maxDb: Double)

/** Independent right-axis limits for visible acoustic curves; never editable EQ gain coordinates. */
internal fun acousticScale(overlay: AcousticOverlay?, target: SampledCatalogResponse? = null): AcousticScale? {
    var minimum = Double.POSITIVE_INFINITY
    var maximum = Double.NEGATIVE_INFINITY
    fun include(values: DoubleArray) {
        for (value in values) if (value.isFinite()) {
            if (value < minimum) minimum = value
            if (value > maximum) maximum = value
        }
    }
    overlay?.referenceDb?.let(::include)
    overlay?.predictedDb?.let(::include)
    target?.referenceDb?.let(::include)
    return acousticScaleLimits(minimum, maximum)
}

internal fun acousticScaleLimits(minimum: Double, maximum: Double): AcousticScale? {
    if (!minimum.isFinite() || !maximum.isFinite()) return null
    var low = floor((minimum - 3.0) / 6.0) * 6.0
    var high = ceil((maximum + 3.0) / 6.0) * 6.0
    if (high - low < 12.0) {
        low -= 6.0
        high += 6.0
    }
    if (!low.isFinite() || !high.isFinite() || !(high - low).isFinite() || high - low < 12.0) return null
    return AcousticScale(low, high)
}

/** Generate only viewport-sized ticks; imported finite SPL magnitudes are not bounded. */
internal fun acousticAxisTicks(scale: AcousticScale, maxLabels: Int): DoubleArray {
    require(maxLabels >= 2)
    val steps = (scale.maxDb - scale.minDb) / 6.0
    val stride = maxOf(1.0, ceil(steps / (maxLabels - 1)))
    val interior = minOf(maxLabels - 2, (ceil(steps / stride) - 1).toInt().coerceAtLeast(0))
    return DoubleArray(interior + 2) { index ->
        when (index) {
            0 -> scale.maxDb
            interior + 1 -> scale.minDb
            else -> scale.maxDb - index * stride * 6.0
        }
    }
}
