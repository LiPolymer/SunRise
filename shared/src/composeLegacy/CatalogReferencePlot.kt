package ink.lipoly.app.sunrise.composeLegacy

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import ink.lipoly.app.sunrise.catalog.SampledCatalogResponse
import ink.lipoly.app.sunrise.catalog.acousticAxisTicks
import ink.lipoly.app.sunrise.catalog.acousticScaleLimits
import kotlin.math.ln
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round

/** Read-only graph. Sampling is owned by the shared catalogue module, paths by this size cache. */
@Composable
internal fun CatalogReferencePlot(
    first: SampledCatalogResponse,
    second: SampledCatalogResponse?,
    english: Boolean,
    modifier: Modifier = Modifier,
) {
    val range = remember(first, second) {
        var minimum = Double.POSITIVE_INFINITY
        var maximum = Double.NEGATIVE_INFINITY
        fun include(values: DoubleArray) {
            for (value in values) {
                minimum = minOf(minimum, value)
                maximum = maxOf(maximum, value)
            }
        }
        include(first.referenceDb)
        second?.let { include(it.referenceDb) }
        acousticScaleLimits(minimum, maximum)
    }
    if (range == null) {
        Text(tr(english, "参考数值范围无法显示。", "Reference values exceed the displayable range."), modifier)
        return
    }
    val textMeasurer = rememberTextMeasurer()
    val textStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val firstColor = MaterialTheme.colorScheme.onSurface
    val secondColor = MaterialTheme.colorScheme.primary
    Box(modifier.semantics {
        contentDescription = tr(english,
            "只读型号参考频响，横轴为 20 Hz 至 20 kHz 的对数频率；仅绘制资料覆盖范围",
            "Read-only model reference response; logarithmic frequency from 20 Hz to 20 kHz; measured coverage only")
    }.drawWithCache {
        val top = 12.dp.toPx()
        val height = (size.height - top - 30.dp.toPx()).coerceAtLeast(0f)
        if (height <= 0f || size.width <= 0f) return@drawWithCache onDrawBehind {}
        val labelHeight = textMeasurer.measure("0", textStyle).size.height
        val maxLabels = (height / (labelHeight + 2.dp.toPx())).toInt().coerceAtLeast(2)
        val dbLabels = acousticAxisTicks(range, maxLabels).map { db ->
            db to textMeasurer.measure(acousticAxisLabel(db), textStyle)
        }
        val left = maxOf(42.dp.toPx(), dbLabels.maxOf { it.second.size.width }.toFloat() + 4.dp.toPx())
        val width = (size.width - left - 16.dp.toPx()).coerceAtLeast(0f)
        if (width <= 0f) return@drawWithCache onDrawBehind {}
        fun x(hz: Double): Float = left + (ln(hz / 20.0) / ln(1000.0) * width).toFloat()
        fun y(db: Double): Float = top + ((range.maxDb - db) / (range.maxDb - range.minDb) * height).toFloat()
        fun path(sampled: SampledCatalogResponse): Path = Path().apply {
            for (index in sampled.frequencyHz.indices) {
                val px = x(sampled.frequencyHz[index])
                val py = y(sampled.referenceDb[index])
                if (index == 0) moveTo(px, py) else lineTo(px, py)
            }
        }
        val firstPath = path(first)
        val secondPath = second?.let { path(it) }
        val solid = Stroke(2.dp.toPx())
        val dashed = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())))
        val frequencies = intArrayOf(20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000)
        val frequencyLabels = frequencies.map { hz ->
            val layout = textMeasurer.measure(if (hz >= 1000) "${hz / 1000}k" else hz.toString(), textStyle)
            Triple(hz, layout, (x(hz.toDouble()) - layout.size.width / 2).coerceIn(0f, (size.width - layout.size.width).coerceAtLeast(0f)))
        }
        val visibleLabels = mutableListOf(frequencyLabels.first())
        val last = frequencyLabels.last()
        for (entry in frequencyLabels.drop(1).dropLast(1)) {
            val prior = visibleLabels.last()
            if (entry.third >= prior.third + prior.second.size.width + 8.dp.toPx() && entry.third + entry.second.size.width + 8.dp.toPx() <= last.third) visibleLabels += entry
        }
        visibleLabels += last
        onDrawBehind {
            for (hz in frequencies) drawLine(gridColor, Offset(x(hz.toDouble()), top), Offset(x(hz.toDouble()), top + height))
            for ((index, entry) in dbLabels.withIndex()) {
                val (db, layout) = entry
                val labelY = y(db)
                drawLine(gridColor, Offset(left, labelY), Offset(left + width, labelY))
                val above = dbLabels.getOrNull(index - 1)?.first?.let(::y) ?: Float.NEGATIVE_INFINITY
                val below = dbLabels.getOrNull(index + 1)?.first?.let(::y) ?: Float.POSITIVE_INFINITY
                if (db == range.minDb || db == range.maxDb ||
                    labelY - above >= layout.size.height + 2.dp.toPx() && below - labelY >= layout.size.height + 2.dp.toPx()) {
                    drawText(layout, topLeft = Offset(0f, labelY - layout.size.height / 2))
                }
            }
            for ((_, layout, position) in visibleLabels) drawText(layout, topLeft = Offset(position, top + height + 6.dp.toPx()))
            clipRect(left, top, left + width, top + height) {
                drawPath(firstPath, firstColor, style = solid)
                if (first.frequencyHz.size == 1) drawCircle(firstColor, 2.dp.toPx(), Offset(x(first.frequencyHz[0]), y(first.referenceDb[0])))
                if (secondPath != null) drawPath(secondPath, secondColor, style = dashed)
            }
        }
    })
}

internal fun acousticAxisLabel(value: Double): String {
    if (abs(value) < 1e6) return value.toString().removeSuffix(".0")
    val exponent = floor(log10(abs(value))).toInt()
    val mantissa = round(value / 10.0.pow(exponent) * 100.0) / 100.0
    return "${mantissa.toString().removeSuffix(".0")}e$exponent"
}
