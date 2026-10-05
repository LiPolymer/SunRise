package ink.lipoly.app.sunrise.catalog

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/** Shared display grid; immutable after construction. Both catalogue and EQ overlays use it. */
internal val catalogFrequencyGrid: DoubleArray = run {
    val start = ln(20.0)
    val step = ln(1000.0) / 767
    DoubleArray(768) { index ->
        when (index) {
            0 -> 20.0
            767 -> 20000.0
            else -> exp(start + index * step)
        }
    }
}

internal data class SampledCatalogResponse(
    val frequencyHz: DoubleArray,
    val referenceDb: DoubleArray,
    val normalizationHz: Double?,
)

private fun interpolateLogSegment(
    lowerDb: Double, upperDb: Double, lowerLogHz: Double, upperLogHz: Double, frequencyHz: Double,
): Double = lowerDb + (upperDb - lowerDb) * (ln(frequencyHz) - lowerLogHz) / (upperLogHz - lowerLogHz)

/** A single lookup in log-frequency space. Outside the measured range is an error, never extrapolated. */
internal fun interpolateCatalogSpl(reference: FrequencyResponse, frequencyHz: Double): Double {
    val frequencies = reference.frequencyHz
    require(frequencyHz.isFinite() && frequencyHz >= frequencies.first() && frequencyHz <= frequencies.last()) {
        "Frequency must be finite and within the reference range"
    }
    val index = frequencies.binarySearch(frequencyHz)
    if (index >= 0) return reference.splDb[index]
    val upper = -index - 1
    return interpolateLogSegment(
        reference.splDb[upper - 1], reference.splDb[upper],
        ln(frequencies[upper - 1]), ln(frequencies[upper]), frequencyHz,
    )
}

/** O(source points + grid points), including exact intersection endpoints without invented coverage. */
internal fun sampleCatalogResponse(reference: FrequencyResponse): SampledCatalogResponse {
    val frequencies = reference.frequencyHz
    val low = max(20.0, frequencies.first())
    val high = min(20000.0, frequencies.last())
    val normalizationHz = if (frequencies.first() <= 500.0 && frequencies.last() >= 500.0) 500.0 else null
    if (low > high) return SampledCatalogResponse(DoubleArray(0), DoubleArray(0), normalizationHz)
    val interiorCount = catalogFrequencyGrid.count { it > low && it < high }
    val sampledHz = DoubleArray(interiorCount + if (low == high) 1 else 2)
    sampledHz[0] = low
    var output = 1
    for (frequency in catalogFrequencyGrid) if (frequency > low && frequency < high) sampledHz[output++] = frequency
    if (low != high) sampledHz[output] = high
    val offset = normalizationHz?.let { interpolateCatalogSpl(reference, it) } ?: 0.0
    val sampledDb = DoubleArray(sampledHz.size)
    var lower = 0
    var lowerLog = ln(frequencies[0])
    var upperLog = ln(frequencies[1])
    for (index in sampledHz.indices) {
        val frequency = sampledHz[index]
        while (lower < frequencies.lastIndex - 1 && frequencies[lower + 1] < frequency) {
            lower++
            lowerLog = upperLog
            upperLog = ln(frequencies[lower + 1])
        }
        val spl = when (frequency) {
            frequencies[lower] -> reference.splDb[lower]
            frequencies[lower + 1] -> reference.splDb[lower + 1]
            else -> interpolateLogSegment(reference.splDb[lower], reference.splDb[lower + 1], lowerLog, upperLog, frequency)
        }
        sampledDb[index] = spl - offset
    }
    return SampledCatalogResponse(sampledHz, sampledDb, normalizationHz)
}
