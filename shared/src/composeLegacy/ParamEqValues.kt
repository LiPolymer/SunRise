package ink.lipoly.app.sunrise.composeLegacy

import ink.lipoly.app.sunrise.drop.GaiaPeqBand
import ink.lipoly.app.sunrise.drop.GaiaBluetrumPeqCodec
import ink.lipoly.app.sunrise.drop.PeqFilter
import kotlin.math.*

internal enum class PeqParameter { GAIN, FREQUENCY, Q }

internal const val PEQ_MIN_Q_SLIDER = 409
internal const val PEQ_MAX_Q_RAW = 65535

internal fun peqFrequencyFraction(hz: Int): Float = (ln(hz.coerceIn(20, 20000) / 20.0) / ln(1000.0)).toFloat()
internal fun peqFrequencyAt(fraction: Float): Int = (20.0 * exp(fraction.coerceIn(0f, 1f) * ln(1000.0))).roundToInt().coerceIn(20, 20000)
internal fun peqQFraction(raw: Int): Float = (ln(raw.coerceIn(PEQ_MIN_Q_SLIDER, PEQ_MAX_Q_RAW) / PEQ_MIN_Q_SLIDER.toDouble()) / ln(PEQ_MAX_Q_RAW / PEQ_MIN_Q_SLIDER.toDouble())).toFloat()
internal fun peqQAt(fraction: Float): Int = when {
    fraction <= 0f -> PEQ_MIN_Q_SLIDER
    fraction >= 1f -> PEQ_MAX_Q_RAW
    else -> (PEQ_MIN_Q_SLIDER * exp(fraction * ln(PEQ_MAX_Q_RAW / PEQ_MIN_Q_SLIDER.toDouble()))).toInt().coerceIn(PEQ_MIN_Q_SLIDER, PEQ_MAX_Q_RAW)
}
internal fun peqGainAt(fraction: Float): Int = ((fraction.coerceIn(0f, 1f) * 240).roundToInt() - 120) * 6
internal fun peqGainFraction(raw: Int): Float = (raw + 720) / 1440f
internal fun peqSliderInRange(band: GaiaPeqBand, parameter: PeqParameter): Boolean = when (parameter) {
    PeqParameter.GAIN -> band.gainRaw in -720..720
    PeqParameter.FREQUENCY -> band.frequencyHz in 20..20000
    PeqParameter.Q -> band.qRaw in PEQ_MIN_Q_SLIDER..PEQ_MAX_Q_RAW
}
internal fun peqHasGain(filter: PeqFilter): Boolean = filter != PeqFilter.LOW_PASS && filter != PeqFilter.HIGH_PASS && filter != PeqFilter.BYPASS
internal fun peqRaw(band: GaiaPeqBand, parameter: PeqParameter): Int = when (parameter) {
    PeqParameter.GAIN -> band.gainRaw
    PeqParameter.FREQUENCY -> band.frequencyHz
    PeqParameter.Q -> band.qRaw
}
internal fun peqWithRaw(band: GaiaPeqBand, parameter: PeqParameter, raw: Int): GaiaPeqBand = when (parameter) {
    PeqParameter.GAIN -> band.copy(gainRaw = raw)
    PeqParameter.FREQUENCY -> band.copy(frequencyHz = raw)
    PeqParameter.Q -> band.copy(qRaw = raw)
}
internal fun peqNudge(band: GaiaPeqBand, parameter: PeqParameter, direction: Int): GaiaPeqBand = peqWithRaw(band, parameter, when (parameter) {
    PeqParameter.GAIN -> (band.gainRaw + direction * 6).coerceIn(-720, 720)
    PeqParameter.FREQUENCY -> (band.frequencyHz * 2.0.pow(direction / 24.0)).roundToInt().coerceIn(20, 20000)
    PeqParameter.Q -> (band.qRaw * 1.05.pow(direction)).toInt().coerceIn(PEQ_MIN_Q_SLIDER, PEQ_MAX_Q_RAW)
})
internal fun peqReset(band: GaiaPeqBand): GaiaPeqBand = band.copy(
    filter = PeqFilter.PEAKING, frequencyHz = band.frequencyHz.takeIf { it in 20..20000 } ?: 1000,
    gainRaw = 0, qRaw = 4096,
)
internal fun peqEnableFilter(band: GaiaPeqBand, filter: PeqFilter): GaiaPeqBand = if (filter == PeqFilter.BYPASS) band.copy(filter = filter)
else band.copy(filter = filter, frequencyHz = band.frequencyHz.takeIf { it in 20..20000 } ?: 1000, qRaw = band.qRaw.takeIf { it in 1..65535 } ?: 4096)
internal fun peqFlatten(bands: List<GaiaPeqBand>): List<GaiaPeqBand> = bands.map {
    it.copy(filter = PeqFilter.PEAKING, gainRaw = 0, frequencyHz = it.frequencyHz.takeIf { hz -> hz in 20..20000 } ?: 1000, qRaw = it.qRaw.takeIf { q -> q in 1..65535 } ?: 4096)
}

/** Full unit precision is deliberately distinct from the rounded parameter-card label. */
internal fun peqInputText(band: GaiaPeqBand, parameter: PeqParameter): String = when (parameter) {
    PeqParameter.FREQUENCY -> band.frequencyHz.toString()
    PeqParameter.GAIN -> band.gainDb.toString()
    PeqParameter.Q -> {
        // Q is an exact binary fraction; show its full decimal units, including raw 1.
        val scaled = band.qRaw * 244140625L
        val fraction = (scaled % 1000000000000L).toString().padStart(12, '0').trimEnd('0')
        if (fraction.isEmpty()) (scaled / 1000000000000L).toString()
        else "${scaled / 1000000000000L}.$fraction"
    }
}

private val peqDecimalInput = Regex("[+-]?(?:[0-9]+(?:[.,][0-9]*)?|[.,][0-9]+)(?:[eE][+-]?[0-9]+)?")
private val peqIntegerInput = Regex("[0-9]+")
/** Returns the unchanged raw field if input is unchanged or denotes the original unit value. */
internal fun peqParseInput(text: String, band: GaiaPeqBand, parameter: PeqParameter): Int {
    val trimmed = text.trim()
    require(trimmed.isNotEmpty())
    if (trimmed == peqInputText(band, parameter)) return peqRaw(band, parameter)
    if (parameter == PeqParameter.FREQUENCY) {
        require(peqIntegerInput.matches(trimmed))
        val hz = trimmed.toIntOrNull() ?: throw IllegalArgumentException()
        if (hz == band.frequencyHz) return hz
        require(hz in 20..20000)
        return hz
    }
    require(peqDecimalInput.matches(trimmed))
    val value = trimmed.replace(',', '.').toDoubleOrNull() ?: throw IllegalArgumentException()
    require(value.isFinite())
    val original = if (parameter == PeqParameter.GAIN) band.gainDb else band.q
    if (value == original) return peqRaw(band, parameter)
    return when (parameter) {
        PeqParameter.GAIN -> {
            require(value in -12.0..12.0)
            GaiaBluetrumPeqCodec.gainRaw(value)
        }
        PeqParameter.Q -> {
            require(value >= 1.0 / 4096 && value <= 65535.0 / 4096)
            GaiaBluetrumPeqCodec.qRaw(value).also { require(it in 1..65535) }
        }
    }
}

internal fun peqAxisDb(bands: List<GaiaPeqBand>): Double = max(12.0, ceil((bands.maxOfOrNull { abs(it.gainDb) } ?: 0.0) / 3.0) * 3.0)
internal fun peqFrequencyX(hz: Double, width: Double): Double = if (width <= 0.0 || !width.isFinite()) 0.0 else ln((if (hz.isNaN()) 20.0 else hz.coerceIn(20.0, 20000.0)) / 20.0) / ln(1000.0) * width
internal fun peqXFrequency(x: Double, width: Double): Double = if (width <= 0.0 || !width.isFinite()) 20.0 else 20.0 * exp((if (x.isNaN()) 0.0 else x.coerceIn(0.0, width)) / width * ln(1000.0))
internal fun peqHalfBandwidth(q: Double): Double = asinh(1.0 / (2.0 * q)) / ln(2.0)
internal fun peqQFromHalfBandwidth(octaves: Double): Double = 1.0 / (2.0 * sinh(ln(2.0) * octaves))

/** Relative movement preserves original raw units on the untouched axis, including sub-display precision. */
internal fun peqDraggedFrequency(originalHz: Int, deltaX: Float, width: Float): Int =
    if (deltaX == 0f || width <= 0 || !deltaX.isFinite()) originalHz
    else (originalHz * exp((deltaX / width * ln(1000.0)).coerceIn(-20.0, 20.0))).roundToInt().coerceIn(20, 20000)
internal fun peqDraggedGain(originalRaw: Int, deltaY: Float, height: Float, axis: Double): Int =
    if (deltaY == 0f || height <= 0 || !deltaY.isFinite()) originalRaw
    else (originalRaw - deltaY / height * 2 * axis * 60).toInt().coerceIn(-32768, 32767)
internal fun peqDraggedQ(originalRaw: Int, deltaX: Float, width: Float, side: Int): Int {
    if (deltaX == 0f || width <= 0 || !deltaX.isFinite()) return originalRaw
    val bandwidth = peqHalfBandwidth(originalRaw / 4096.0) + side * deltaX / width * log2(1000.0)
    if (bandwidth <= peqHalfBandwidth(65535.0 / 4096)) return 65535
    if (bandwidth >= peqHalfBandwidth(1.0 / 4096)) return 1
    return (peqQFromHalfBandwidth(bandwidth) * 4096).toInt().coerceIn(1, 65535)
}

internal fun peqPinchedQ(originalRaw: Int, spanDeltaPx: Float, width: Float): Int {
    if (spanDeltaPx == 0f || !spanDeltaPx.isFinite() || width <= 0f || !width.isFinite()) return originalRaw
    return peqDraggedQ(originalRaw, spanDeltaPx / 2f, width, 1)
}

