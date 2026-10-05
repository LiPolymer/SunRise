package ink.lipoly.app.sunrise.catalog

internal class FrequencyResponse(val frequencyHz: DoubleArray, val splDb: DoubleArray)

private val frequencyColumns = Regex("\\s+")
private val rewPhaseHeader = Regex(
    "Freq\\(Hz\\)[\\s,]+SPL\\(dB\\)[\\s,]+Phase\\(degrees\\)",
    RegexOption.IGNORE_CASE,
)

/** Parses magnitude only; an explicitly identified REW phase column is validated, not retained here. */
internal fun parseFrequencyResponse(bytes: ByteArray): FrequencyResponse {
    require(bytes.size <= MAX_RESPONSE_FILE_BYTES) { "Frequency response exceeds 2 MiB (line 1)" }
    var frequencies = DoubleArray(256)
    var magnitudes = DoubleArray(256)
    var pointCount = 0
    var phaseHeader = false
    var columnCount: Int? = null
    var lastLine = 1
    for ((index, originalLine) in bytes.decodeToString().removePrefix("\uFEFF").lineSequence().withIndex()) {
        val lineNumber = index + 1
        lastLine = lineNumber
        val line = originalLine.trim()
        if (line.isEmpty()) continue
        if (line.first() == '*' || line.first() == '#' || line.first() == ';') {
            if (rewPhaseHeader.containsMatchIn(line)) phaseHeader = true
            continue
        }
        fun invalid(reason: String): Nothing =
            throw IllegalArgumentException("Frequency response line $lineNumber: $reason")
        if ('\uFFFD' in line) invalid("invalid UTF-8 in data")
        val columns = if (',' in line) line.split(',').map { it.trim() } else line.split(frequencyColumns)
        if (columns.size != 2 && columns.size != 3) invalid("expected two columns, or REW frequency/SPL/phase")
        if (columns.size == 3 && !phaseHeader) invalid("third column requires the REW Freq(Hz) SPL(dB) Phase(degrees) comment header")
        if (columnCount != null && columnCount != columns.size) invalid("inconsistent column count")
        columnCount = columns.size
        fun number(column: Int): Double {
            val value = columns[column].toDoubleOrNull() ?: invalid("column ${column + 1} is not numeric")
            if (!value.isFinite()) invalid("column ${column + 1} is not finite")
            return value
        }
        val frequency = number(0)
        val magnitude = number(1)
        if (columns.size == 3) number(2)
        if (frequency <= 0.0) invalid("frequency must be positive")
        if (pointCount > 0 && frequency <= frequencies[pointCount - 1]) invalid("frequencies must be strictly increasing")
        if (pointCount == frequencies.size) {
            frequencies = frequencies.copyOf(pointCount * 2)
            magnitudes = magnitudes.copyOf(pointCount * 2)
        }
        frequencies[pointCount] = frequency
        magnitudes[pointCount] = magnitude
        pointCount++
    }
    require(pointCount >= 2) { "Frequency response line $lastLine: at least two data points are required" }
    return FrequencyResponse(
        if (pointCount == frequencies.size) frequencies else frequencies.copyOf(pointCount),
        if (pointCount == magnitudes.size) magnitudes else magnitudes.copyOf(pointCount),
    )
}
