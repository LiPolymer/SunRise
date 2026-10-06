package ink.lipoly.app.sunrise.controls.peq

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import ink.lipoly.app.sunrise.catalog.AcousticOverlay
import ink.lipoly.app.sunrise.catalog.AcousticScale
import ink.lipoly.app.sunrise.catalog.SampledCatalogResponse
import ink.lipoly.app.sunrise.catalog.acousticAxisTicks
import ink.lipoly.app.sunrise.drop.GaiaPeqBand
import ink.lipoly.app.sunrise.drop.PeqFilter
import ink.lipoly.app.sunrise.drop.PeqBiquad
import kotlin.math.*

private fun acousticAxisLabel(value: Double): String {
    if (abs(value) < 1e6) return value.toString().removeSuffix(".0")
    val exponent = floor(log10(abs(value))).toInt()
    val mantissa = round(value / 10.0.pow(exponent) * 100.0) / 100.0
    return "${mantissa.toString().removeSuffix(".0")}e$exponent"
}

private val bandColors = listOf(Color(0xffe69f00), Color(0xff56b4e9), Color(0xff009e73), Color(0xffcc79a7), Color(0xffd55e00), Color(0xff0072b2))
private fun bandColor(index: Int): Color = bandColors[index % bandColors.size]
internal val targetResponseColor = Color(0xff0072b2)

private data class PeqPlot(val left: Float, val top: Float, val width: Float, val height: Float, val axis: Double) {
    fun point(band: GaiaPeqBand): Offset? {
        if (band.filter == PeqFilter.BYPASS && band.qRaw == 0) return null
        if (band.frequencyHz !in 20..20000) return null
        return Offset(x(band.frequencyHz.toDouble()), y(if (peqHasGain(band.filter)) band.gainDb else 0.0))
    }
    fun x(hz: Double): Float = left + peqFrequencyX(hz, width.toDouble()).toFloat()
    fun y(db: Double): Float = top + ((axis - db) / (2 * axis) * height).toFloat()
}

/** Sampled once per draft/size, so selection, hover and confirmation never recalculate frequency response. */
private class PeqCurveGeometry(private val plot: PeqPlot, coefficients: List<PeqBiquad>) {
    private val count = min(1024, max(128, ceil(plot.width / 2).toInt()))
    private val x = FloatArray(count) { plot.left + it.toFloat() / (count - 1) * plot.width }
    private val hz = DoubleArray(count) { peqXFrequency((x[it] - plot.left).toDouble(), plot.width.toDouble()) }
    private val responses = Array(coefficients.size) { band -> DoubleArray(count) { coefficients[band].responseDb(hz[it]) } }
    private val total = DoubleArray(count) { sample ->
        var sum = 0.0
        for (band in responses.indices) sum += responses[band][sample]
        sum
    }
    private fun path(values: DoubleArray, fill: Boolean): Path = Path().apply {
        if (fill) { moveTo(x[0], plot.y(0.0)); lineTo(x[0], plot.y(values[0])) }
        else moveTo(x[0], plot.y(values[0]))
        for (i in 1 until count) lineTo(x[i], plot.y(values[i]))
        if (fill) { lineTo(x[count - 1], plot.y(0.0)); close() }
    }
    val composite = path(total, false)
    val curves = Array(responses.size) { path(responses[it], false) }
    val fills = Array(responses.size) { path(responses[it], true) }
}

/** Acoustic paths use their own dB axis, never the editable gain coordinates. */
private class AcousticCurveGeometry(
    overlay: AcousticOverlay?, target: SampledCatalogResponse?, private val plot: PeqPlot, private val scale: AcousticScale,
) {
    private fun path(frequencyHz: DoubleArray, values: DoubleArray): Path = Path().apply {
        var started = false
        for (i in 0 until min(frequencyHz.size, values.size)) {
            val hz = frequencyHz[i]
            val db = values[i]
            if (!hz.isFinite() || hz !in 20.0..20000.0 || !db.isFinite()) {
                started = false
                continue
            }
            val x = plot.x(hz)
            val y = plot.top + ((scale.maxDb - db) / (scale.maxDb - scale.minDb) * plot.height).toFloat()
            if (started) lineTo(x, y) else { moveTo(x, y); started = true }
        }
    }
    val reference = overlay?.let { path(it.frequencyHz, it.referenceDb) }
    val prediction = overlay?.let { source -> source.predictedDb?.let { path(source.frequencyHz, it) } }
    val target = target?.let { path(it.frequencyHz, it.referenceDb) }
}

/** Canvas is supplementary: the real sliders, selector and numeric fields expose the same edits. */
@Composable
internal fun ParamEqCurve(
    bands: List<GaiaPeqBand>, selectedIndex: Int, editor: ParamEqEditor?, enabled: Boolean,
    english: Boolean, compact: Boolean, onSelect: (Int) -> Unit, modifier: Modifier = Modifier,
    overlay: AcousticOverlay?,
    target: SampledCatalogResponse?,
    responseScale: AcousticScale?,
) {
    val density = LocalDensity.current
    val viewConfiguration = LocalViewConfiguration.current
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val compositeColor = MaterialTheme.colorScheme.onSurface
    val focusRequester = remember { FocusRequester() }
    var measuredSize by remember { mutableStateOf(IntSize.Zero) }
    var mouse by remember { mutableStateOf(false) }
    val hasResponse = responseScale != null
    val leftMargin = with(density) { 32.dp.toPx() }
    val rightMargin = with(density) { (if (hasResponse) 56.dp else 32.dp).toPx() }
    val bottom = with(density) { 30.dp.toPx() }
    val top = with(density) { 20.dp.toPx() }
    val axis = remember(bands) { peqAxisDb(bands) }
    val plot = remember(measuredSize, leftMargin, rightMargin, top, bottom, axis) {
        PeqPlot(leftMargin, top, (measuredSize.width - leftMargin - rightMargin).coerceAtLeast(0f), (measuredSize.height - top - bottom).coerceAtLeast(0f), axis)
    }
    val latestSelected by rememberUpdatedState(selectedIndex)
    val latestEnabled = rememberUpdatedState(enabled)
    val latestSelect by rememberUpdatedState(onSelect)
    val latestPlot by rememberUpdatedState(plot)
    val coefficients = remember(bands) { bands.map { PeqBiquad.of(it) } }
    val geometry = remember(coefficients, plot) {
        if (coefficients.isNotEmpty() && plot.width > 0 && plot.height > 0) PeqCurveGeometry(plot, coefficients) else null
    }
    val acousticGeometry = remember(overlay, target, plot, responseScale) {
        if ((overlay != null || target != null) && responseScale != null && plot.width > 0 && plot.height > 0)
            AcousticCurveGeometry(overlay, target, plot, responseScale) else null
    }
    fun oneEdit(changed: GaiaPeqBand) {
        if (!latestEnabled.value || editor == null || editor.state.value.isEditing) return
        val current = editor.state.value.draft.firstOrNull { it.index == changed.index } ?: return
        if (current == changed) return
        editor.beginEdit()
        editor.editBand(changed)
        editor.endEdit()
    }
    Box(modifier
        .onSizeChanged { measuredSize = it }
        .semantics {
            contentDescription = if (hasResponse && editor == null)
                tr(english, "离线参考/目标频响；右轴为参考 dB，当前不能编辑 EQ，也没有预测。", "Offline reference/target response; right axis shows reference dB. EQ is not editable and there is no prediction.")
            else if (hasResponse)
                tr(english, "左轴 EQ dB 用于编辑；右轴参考/目标频响及可用时的非实测预测。双指横向开合调整所选频段 Q，或使用下方滑杆编辑",
                    "Left EQ dB axis is editable; right axis shows reference/target response and non-measured prediction when available. Spread or pinch two fingers horizontally to adjust the selected band Q, or use the sliders below")
            else tr(english, "参数响应估算；双指横向开合调整所选频段 Q，或使用下方滑杆编辑", "Estimated parameter response; spread or pinch two fingers horizontally to adjust the selected band Q, or use the sliders below")
        }
        .focusRequester(focusRequester)
        .onKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown || !latestEnabled.value || editor == null || editor.state.value.isEditing) return@onKeyEvent false
            val band = editor.state.value.draft.firstOrNull { it.index == latestSelected } ?: return@onKeyEvent false
            if (band.filter == PeqFilter.BYPASS) return@onKeyEvent false
            val frequencyFactor = when { event.isCtrlPressed -> 1.001; event.isShiftPressed -> 1.1; else -> 1.01 }
            val gainStep = when { event.isCtrlPressed -> 1; event.isShiftPressed -> 60; else -> 6 }
            val changed = when (event.key) {
                Key.DirectionLeft -> band.copy(frequencyHz = (band.frequencyHz / frequencyFactor).roundToInt().coerceIn(20, 20000))
                Key.DirectionRight -> band.copy(frequencyHz = (band.frequencyHz * frequencyFactor).roundToInt().coerceIn(20, 20000))
                Key.DirectionUp -> if (peqHasGain(band.filter)) band.copy(gainRaw = (band.gainRaw + gainStep).coerceIn(-32768, 32767)) else band
                Key.DirectionDown -> if (peqHasGain(band.filter)) band.copy(gainRaw = (band.gainRaw - gainStep).coerceIn(-32768, 32767)) else band
                else -> return@onKeyEvent false
            }
            oneEdit(changed)
            true
        }
        .focusable(enabled)
        .pointerInput(editor, density) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.changes.any { it.type == PointerType.Mouse }) mouse = true
                    if (event.type != PointerEventType.Scroll || !latestEnabled.value || editor == null || editor.state.value.isEditing) continue
                    val band = editor.state.value.draft.firstOrNull { it.index == latestSelected } ?: continue
                    if (band.filter == PeqFilter.BYPASS || band.qRaw == 0) continue
                    val scroll = event.changes.firstOrNull()?.scrollDelta?.y ?: continue
                    if (scroll != 0f) {
                        oneEdit(band.copy(qRaw = (band.qRaw * 2.0.pow(-scroll / 8.0)).toInt().coerceIn(1, 65535)))
                        event.changes.forEach { it.consume() }
                    }
                }
            }
        }
        .pointerInput(editor, density) {
            var lastClickIndex = -1
            var lastClickTime = 0L
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val p = latestPlot
                if (p.width <= 0 || p.height <= 0 || !latestEnabled.value || editor == null || editor.state.value.isEditing) return@awaitEachGesture
                mouse = down.type == PointerType.Mouse
                val radius = with(density) { (if (mouse) 24.dp else 28.dp).toPx() }
                val currentBands = editor.state.value.draft
                val selected = currentBands.firstOrNull { it.index == latestSelected }
                var handleSide = 0
                val nearCenter = selected?.let { p.point(it)?.let { point -> (point - down.position).getDistance() <= with(density) { 12.dp.toPx() } } } == true
                if (mouse && !nearCenter && selected != null && selected.filter != PeqFilter.BYPASS && selected.qRaw > 0) {
                    val half = peqHalfBandwidth(selected.q)
                    val centerY = p.point(selected)?.y ?: p.y(0.0)
                    for (side in listOf(-1, 1)) {
                        val point = Offset(p.x(selected.frequencyHz * 2.0.pow(side * half)), centerY)
                        if ((point - down.position).getDistance() <= with(density) { 14.dp.toPx() }) handleSide = side
                    }
                }
                val hit = if (handleSide != 0) selected else currentBands
                    .filter { band -> p.point(band)?.let { (it - down.position).getDistance() <= radius } == true }
                    .minWithOrNull(compareBy<GaiaPeqBand> { if (it.index == latestSelected) 0 else 1 }.thenBy { (p.point(it)!! - down.position).getDistance() }.thenBy { it.index })
                if (hit == null && (down.type != PointerType.Touch || down.isConsumed ||
                        down.position.x !in p.left..(p.left + p.width) ||
                        down.position.y !in p.top..(p.top + p.height))) return@awaitEachGesture
                if (down.type == PointerType.Touch && down.isConsumed) return@awaitEachGesture
                hit?.let {
                    latestSelect(it.index)
                    focusRequester.requestFocus()
                }
                var claimed = false
                var groupStarted = false
                var removedSlop = Offset.Zero
                var lockedAxis = 0
                var released = false
                var secondId: PointerId? = null
                var pairEnded = false
                var pairIndex = -1
                var initialSpan = 0f
                var initialQ = 0
                var pinchWidth = 0f
                var firstEvent = true
                try {
                    while (true) {
                        // awaitFirstDown may return an event containing both touch downs.
                        val event = if (firstEvent) {
                            firstEvent = false
                            currentEvent
                        } else awaitPointerEvent()
                        val pointer = event.changes.firstOrNull { it.id == down.id }
                        if (secondId != null) {
                            val second = event.changes.firstOrNull { it.id == secondId }
                            val now = editor.state.value.draft.firstOrNull { it.index == pairIndex }
                            if (pointer?.pressed != true || second?.pressed != true || !latestEnabled.value ||
                                now == null || now.filter == PeqFilter.BYPASS || now.qRaw <= 0 ||
                                (!groupStarted && editor.state.value.isEditing)) pairEnded = true
                            if (!pairEnded && pointer != null && second != null && now != null) {
                                val difference = abs(pointer.position.x - second.position.x) - initialSpan
                                val delta = sign(difference) * max(abs(difference) - viewConfiguration.touchSlop, 0f)
                                val raw = peqPinchedQ(initialQ, delta, pinchWidth)
                                if (raw != now.qRaw) {
                                    if (!groupStarted) { editor.beginEdit(); groupStarted = true }
                                    editor.editBand(now.copy(qRaw = raw))
                                }
                            }
                            // Once a pair is admitted, never fall back to node drag or replace a lost finger.
                            event.changes.forEach { it.consume() }
                            if (event.changes.none { it.pressed }) break
                            continue
                        }
                        if (pointer == null) break
                        if (!pointer.pressed) { released = true; break }
                        if (!latestEnabled.value || (!claimed && pointer.isConsumed) ||
                            (!groupStarted && editor.state.value.isEditing)) break
                        if (down.type == PointerType.Touch) {
                            val second = event.changes.firstOrNull {
                                it.id != down.id && it.type == PointerType.Touch && it.changedToDown() &&
                                    it.position.x in p.left..(p.left + p.width) &&
                                    it.position.y in p.top..(p.top + p.height)
                            }
                            val target = editor.state.value.draft.firstOrNull { it.index == (hit?.index ?: latestSelected) }
                            if (second != null && pointer.position.x in p.left..(p.left + p.width) &&
                                pointer.position.y in p.top..(p.top + p.height) &&
                                target != null && target.filter != PeqFilter.BYPASS && target.qRaw > 0) {
                                secondId = second.id
                                pairIndex = target.index
                                initialQ = target.qRaw
                                initialSpan = abs(pointer.position.x - second.position.x)
                                pinchWidth = p.width
                                claimed = true
                                event.changes.forEach { it.consume() }
                                continue
                            }
                        }
                        if (hit == null) {
                            // Observe the parent's final consumption; blank single-finger scrolling wins.
                            if (awaitPointerEvent(PointerEventPass.Final).changes.any { it.id == down.id && it.isConsumed }) break
                            continue
                        }
                        val displacement = pointer.position - down.position
                        if (!claimed) {
                            val distance = displacement.getDistance()
                            if (distance <= viewConfiguration.touchSlop) {
                                if (down.type == PointerType.Touch &&
                                    awaitPointerEvent(PointerEventPass.Final).changes.any { it.id == down.id && it.isConsumed }) break
                                continue
                            }
                            claimed = true
                            removedSlop = displacement * (viewConfiguration.touchSlop / distance)
                            lockedAxis = if (abs(displacement.x) >= abs(displacement.y)) 1 else 2
                        }
                        val delta = displacement - removedSlop
                        val now = editor.state.value.draft.firstOrNull { it.index == hit.index } ?: break
                        val changed = if (now.filter == PeqFilter.BYPASS) now else if (handleSide != 0) {
                            // Use the original (unclipped) handle distance; grabbing a clipped handle does not change Q.
                            now.copy(qRaw = peqDraggedQ(hit.qRaw, delta.x, p.width, handleSide))
                        } else {
                            val shift = mouse && event.keyboardModifiers.isShiftPressed
                            val dx = if (shift && lockedAxis == 2) 0f else delta.x
                            val dy = if (shift && lockedAxis == 1) 0f else delta.y
                            now.copy(frequencyHz = peqDraggedFrequency(hit.frequencyHz, dx, p.width), gainRaw = if (peqHasGain(now.filter)) peqDraggedGain(hit.gainRaw, dy, p.height, p.axis) else now.gainRaw)
                        }
                        if (changed != now) {
                            if (!groupStarted) { editor.beginEdit(); groupStarted = true }
                            editor.editBand(changed)
                        }
                        pointer.consume()
                    }
                    if (!claimed && released && mouse && handleSide == 0 && hit != null) {
                        if (lastClickIndex == hit.index && down.uptimeMillis - lastClickTime <= viewConfiguration.doubleTapTimeoutMillis) {
                            editor.state.value.draft.firstOrNull { it.index == hit.index }?.let { oneEdit(peqReset(it)) }
                            lastClickIndex = -1
                        } else {
                            lastClickIndex = hit.index
                            lastClickTime = down.uptimeMillis
                        }
                    }
                } finally {
                    // System cancellation and pointer loss commit the last value, just like normal release.
                    if (groupStarted) editor.endEdit()
                }
            }
        }
        .drawWithCache {
            val p = PeqPlot(leftMargin, top, (size.width - leftMargin - rightMargin).coerceAtLeast(0f), (size.height - top - bottom).coerceAtLeast(0f), axis)
            if (p.width <= 0 || p.height <= 0) return@drawWithCache onDrawBehind {}
            val totalPath = geometry?.composite
            val selectedPosition = bands.indexOfFirst { it.index == selectedIndex }
            val selectedPath = geometry?.curves?.getOrNull(selectedPosition)
            val selectedFill = geometry?.fills?.getOrNull(selectedPosition)
            val ticks = intArrayOf(20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000)
            val frequencyGridX = FloatArray(ticks.size) { p.x(ticks[it].toDouble()) }
            val desired = if (compact) intArrayOf(20, 100, 1000, 10000, 20000) else ticks
            val labels = desired.map { hz ->
                val label = textMeasurer.measure(if (hz >= 1000) "${hz / 1000}k" else hz.toString(), labelStyle.copy(color = textColor))
                Triple(hz, label, (p.x(hz.toDouble()) - label.size.width / 2).coerceIn(0f, (size.width - label.size.width).coerceAtLeast(0f)))
            }
            val visible = mutableListOf(labels.first())
            val last = labels.last()
            for (entry in labels.drop(1).dropLast(1)) {
                val prior = visible.last()
                if (entry.third >= prior.third + prior.second.size.width + 8.dp.toPx() && entry.third + entry.second.size.width + 8.dp.toPx() <= last.third) visible += entry
            }
            visible += last
            val dbLabels = (-axis.toInt()..axis.toInt() step 3).map { db ->
                db to textMeasurer.measure(db.toString(), labelStyle.copy(color = textColor))
            }
            val eqCaption = textMeasurer.measure("EQ dB", labelStyle.copy(color = textColor))
            val acousticCaption = if (hasResponse) textMeasurer.measure(
                if (overlay?.normalizationHz != null || (overlay == null && target?.normalizationHz != null)) tr(english, "参考 dB · 500 Hz=0", "Reference dB · 500 Hz=0")
                else tr(english, "原始 SPL dB", "Raw SPL dB"), labelStyle.copy(color = textColor)
            ) else null
            val rightLabels = responseScale?.let { scale ->
                val maxLabels = (p.height / (eqCaption.size.height + 2.dp.toPx())).toInt().coerceAtLeast(2)
                val candidates = acousticAxisTicks(scale, maxLabels).map { db ->
                    val layout = textMeasurer.measure(acousticAxisLabel(db), labelStyle.copy(color = textColor))
                    val y = p.top + ((scale.maxDb - db) / (scale.maxDb - scale.minDb) * p.height).toFloat()
                    layout to (y - layout.size.height / 2).coerceIn(p.top, (p.top + p.height - layout.size.height).coerceAtLeast(p.top))
                }
                val result = mutableListOf(candidates.first())
                val lastLabel = candidates.last()
                for (entry in candidates.drop(1).dropLast(1)) {
                    val prior = result.last()
                    if (entry.second >= prior.second + prior.first.size.height + 2.dp.toPx() &&
                        entry.second + entry.first.size.height + 2.dp.toPx() <= lastLabel.second) result += entry
                }
                result += lastLabel
                result
            }.orEmpty()
            val nodes = bands.mapNotNull { band -> p.point(band)?.let { Triple(band, it, textMeasurer.measure((band.index + 1).toString(), labelStyle.copy(color = Color.White))) } }
            val selectedBand = bands.firstOrNull { it.index == selectedIndex }
            val handles = if (mouse && selectedBand != null && selectedBand.qRaw > 0 && selectedBand.filter != PeqFilter.BYPASS) {
                val half = peqHalfBandwidth(selectedBand.q)
                val y = p.point(selectedBand)?.y ?: p.y(0.0)
                listOf(Offset(p.x(selectedBand.frequencyHz * 2.0.pow(-half)), y), Offset(p.x(selectedBand.frequencyHz * 2.0.pow(half)), y))
            } else emptyList()
            val selectedStroke = Stroke(1.5.dp.toPx())
            val compositeStroke = Stroke(2.5.dp.toPx())
            val nativeStroke = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())))
            val predictionStroke = Stroke(2.dp.toPx())
            val targetStroke = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())))
            onDrawBehind {
                drawText(eqCaption, topLeft = Offset(p.left, 0f))
                acousticCaption?.let { drawText(it, topLeft = Offset((p.left + p.width - it.size.width).coerceAtLeast(p.left + eqCaption.size.width + 8.dp.toPx()), 0f)) }
                frequencyGridX.forEach { x -> drawLine(gridColor, Offset(x, p.top), Offset(x, p.top + p.height)) }
                dbLabels.forEach { (db, layout) ->
                    drawLine(gridColor, Offset(p.left, p.y(db.toDouble())), Offset(p.left + p.width, p.y(db.toDouble())), if (db == 0) 2f else 1f)
                    // Large gain ranges retain grid lines, but omit colliding dB labels.
                    if (db == 0 || abs(db) == axis.toInt() || p.height / (2 * axis) * 3 >= layout.size.height + 2) drawText(layout, topLeft = Offset(0f, p.y(db.toDouble()) - layout.size.height / 2))
                }
                visible.forEach { (_, layout, x) -> drawText(layout, topLeft = Offset(x, p.top + p.height + 6.dp.toPx())) }
                rightLabels.forEach { (layout, y) -> drawText(layout, topLeft = Offset(p.left + p.width + 6.dp.toPx(), y)) }
                clipRect(p.left, p.top, p.left + p.width, p.top + p.height) {
                    if (selectedFill != null && selectedPath != null) {
                        drawPath(selectedFill, bandColor(selectedIndex).copy(alpha = 0.14f * if (hasResponse) 0.55f else 1f))
                        drawPath(selectedPath, bandColor(selectedIndex).copy(alpha = if (hasResponse) 0.55f else 1f), style = selectedStroke)
                    }
                    if (totalPath != null) drawPath(totalPath, compositeColor.copy(alpha = if (hasResponse) 0.30f else 1f), style = compositeStroke)
                    acousticGeometry?.let {
                        it.reference?.let { reference -> drawPath(reference, compositeColor.copy(alpha = 0.65f), style = nativeStroke) }
                        it.prediction?.let { prediction -> drawPath(prediction, compositeColor, style = predictionStroke) }
                        it.target?.let { target -> drawPath(target, targetResponseColor, style = targetStroke) }
                    }
                }
                handles.forEach { point -> drawLine(bandColor(selectedIndex), Offset(point.x, point.y - 10.dp.toPx()), Offset(point.x, point.y + 10.dp.toPx()), 4.dp.toPx()) }
                nodes.forEach { (band, point, label) ->
                    if (band.index != selectedIndex) {
                        drawCircle(bandColor(band.index), 10.dp.toPx(), point)
                        drawText(label, topLeft = Offset(point.x - label.size.width / 2, point.y - label.size.height / 2))
                    }
                }
                nodes.firstOrNull { it.first.index == selectedIndex }?.let { (band, point, label) ->
                    drawCircle(bandColor(band.index).copy(alpha = 0.25f), 18.dp.toPx(), point)
                    drawCircle(bandColor(band.index), 12.dp.toPx(), point)
                    drawText(label, topLeft = Offset(point.x - label.size.width / 2, point.y - label.size.height / 2))
                }
            }
        })
}
