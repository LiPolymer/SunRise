package ink.lipoly.app.sunrise.compose

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
import ink.lipoly.app.sunrise.drop.DropException
import ink.lipoly.app.sunrise.drop.GaiaControls
import ink.lipoly.app.sunrise.drop.GaiaParamEqState
import ink.lipoly.app.sunrise.drop.GaiaPeqBand
import ink.lipoly.app.sunrise.drop.GaiaPeqParameters
import ink.lipoly.app.sunrise.drop.PeqBiquad
import ink.lipoly.app.sunrise.drop.PeqFilter
import kotlin.math.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** EQ curve rendering, session-scoped editing state and raw-preserving parameter tools. */
object FancyEqualizer {
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
        compact: Boolean, onSelect: (Int) -> Unit, modifier: Modifier = Modifier,
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
                    "离线参考/目标频响；右轴为参考 dB，当前不能编辑 EQ，也没有预测。"
                else if (hasResponse)
                    "左轴 EQ dB 用于编辑；右轴参考/目标频响及可用时的非实测预测。双指横向开合调整所选频段 Q，或使用下方滑杆编辑"
                else "参数响应估算；双指横向开合调整所选频段 Q，或使用下方滑杆编辑"
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
                    if (overlay?.normalizationHz != null || (overlay == null && target?.normalizationHz != null)) "参考 dB · 500 Hz=0"
                    else "原始 SPL dB", labelStyle.copy(color = textColor)
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

    internal enum class ParamEqEditPhase { LOADING, READY, SENT, PENDING, WRITING, FAILED, UNAVAILABLE }

    internal enum class ParamEqSubmitMode { REALTIME, MANUAL }

    internal data class ParamEqEditState(
        val phase: ParamEqEditPhase,
        val confirmed: GaiaParamEqState? = null,
        val lastSent: List<GaiaPeqBand>? = null,
        val draft: List<GaiaPeqBand> = emptyList(),
        val error: Exception? = null,
        val isEditing: Boolean = false,
        val canUndo: Boolean = false,
        val submitMode: ParamEqSubmitMode = ParamEqSubmitMode.REALTIME,
    ) {
        val canSubmit: Boolean
            get() = submitMode == ParamEqSubmitMode.MANUAL && !isEditing &&
                (phase == ParamEqEditPhase.READY || phase == ParamEqEditPhase.PENDING)

        val canChangeSubmitMode: Boolean
            get() = !isEditing && (phase == ParamEqEditPhase.READY || phase == ParamEqEditPhase.SENT)
    }

    /**
     * UI-context-confined editor for one controls/binding lifetime. The one worker owns all device
     * operations; its channel is only a wake-up, never a queue of obsolete parameter snapshots.
     */
    internal class ParamEqEditor(
        scope: CoroutineScope,
        private val controls: GaiaControls,
        private val timeSource: TimeSource = TimeSource.Monotonic,
    ) {
        private val mutableState = MutableStateFlow(ParamEqEditState(ParamEqEditPhase.LOADING))
        val state: StateFlow<ParamEqEditState> = mutableState.asStateFlow()

        private val wake = Channel<Unit>(Channel.CONFLATED)
        private var closed = false
        private var reloadRequested = true
        private var flushRequested = false
        private var editVersion = 0L
        private var queuedSubmission: List<GaiaPeqBand>? = null
        private var inflightVersion: Long? = null
        private var lastWriteStart: TimeMark? = null
        private var editStart: List<GaiaPeqBand>? = null
        private var undoSnapshot: List<GaiaPeqBand>? = null
        private val worker = scope.launch { runWorker() }

        init {
            wake.trySend(Unit)
        }

        fun beginEdit() {
            val current = state.value
            if (closed || !current.allowsEditing() || current.isEditing) return
            editStart = current.draft
            mutableState.value = current.copy(isEditing = true, canUndo = false)
        }

        fun editBand(band: GaiaPeqBand) {
            val current = state.value
            if (closed || !current.allowsEditing()) return
            require(band.index in current.draft.indices) { "Band index is outside the loaded EQ" }
            if (current.draft[band.index] == band) return
            val bands = current.draft.toMutableList()
            bands[band.index] = band
            GaiaPeqParameters.validateBands(bands)
            publishDraft(current, bands)
        }

        /** Publishes one validated complete configuration, including flatten/undo operations. */
        fun replaceDraft(bands: List<GaiaPeqBand>) {
            val current = state.value
            if (closed || !current.allowsEditing()) return
            require(bands.size == current.draft.size) { "Refresh before changing the band count" }
            GaiaPeqParameters.validateBands(bands)
            if (bands == current.draft) return
            publishDraft(current, bands.toList())
        }

        private fun publishDraft(current: ParamEqEditState, snapshot: List<GaiaPeqBand>) {
            editVersion++
            val phase = when {
                inflightVersion != null -> ParamEqEditPhase.WRITING
                snapshot == current.transportBaseline -> current.settledPhase
                else -> ParamEqEditPhase.PENDING
            }
            mutableState.value = current.copy(
                phase = phase,
                draft = snapshot,
                error = null,
                canUndo = canUndo(phase, current.isEditing),
            )
            if (current.submitMode == ParamEqSubmitMode.REALTIME) wake.trySend(Unit)
        }

        fun endEdit() {
            val current = state.value
            if (closed || !current.isEditing) return
            val start = editStart
            editStart = null
            if (current.allowsEditing() && start != null && start != current.draft) {
                undoSnapshot = start
            }
            mutableState.value = current.copy(
                isEditing = false,
                canUndo = canUndo(current.phase, false),
            )
            // A canceled/failed gesture still ends, but must not restart the stopped worker.
            if (current.allowsEditing()) flush()
        }

        fun undo() {
            val current = state.value
            if (closed || !current.canUndo || current.isEditing || !current.allowsEditing()) return
            val snapshot = undoSnapshot ?: return
            undoSnapshot = null
            replaceDraft(snapshot)
            mutableState.value = state.value.copy(canUndo = false)
            flush()
        }

        /** Switching an idle editor never commits a draft or replays an old realtime wake-up. */
        fun setSubmitMode(mode: ParamEqSubmitMode) {
            val current = state.value
            if (closed || !current.canChangeSubmitMode || current.submitMode == mode) return
            discardSignals()
            mutableState.value = current.copy(submitMode = mode)
        }

        /** Capture one complete manual transaction and close the tap/edit gate synchronously. */
        fun submit() {
            val current = state.value
            if (closed || !current.canSubmit) return
            queuedSubmission = current.draft.toList()
            mutableState.value = current.copy(phase = ParamEqEditPhase.WRITING, canUndo = false)
            wake.trySend(Unit)
        }

        /** Release/commit bypasses the next throttle wait, not an already-running transaction. */
        fun flush() {
            val current = state.value
            if (closed || current.submitMode == ParamEqSubmitMode.MANUAL || !current.allowsEditing()) return
            if (inflightVersion == null && current.draft == current.transportBaseline) return
            flushRequested = true
            wake.trySend(Unit)
        }

        /** Manual idle refresh intentionally discards an unsent draft; realtime pending work is retained. */
        fun refresh() {
            val current = state.value
            if (closed || current.isEditing || inflightVersion != null ||
                (current.phase == ParamEqEditPhase.PENDING && current.submitMode == ParamEqSubmitMode.REALTIME) ||
                current.phase == ParamEqEditPhase.WRITING ||
                current.phase == ParamEqEditPhase.LOADING
            ) return
            editVersion++
            editStart = null
            undoSnapshot = null
            discardSignals()
            reloadRequested = true
            mutableState.value = ParamEqEditState(ParamEqEditPhase.LOADING, submitMode = current.submitMode)
            wake.trySend(Unit)
        }

        fun close() {
            if (closed) return
            closed = true
            reloadRequested = false
            discardSignals()
            editStart = null
            undoSnapshot = null
            mutableState.value = ParamEqEditState(ParamEqEditPhase.UNAVAILABLE, submitMode = state.value.submitMode)
            worker.cancel()
            wake.close()
        }

        private suspend fun runWorker() {
            while (wake.receiveCatching().isSuccess) {
                while (!closed) {
                    if (reloadRequested) {
                        reloadRequested = false
                        load()
                        continue
                    }
                    val current = state.value
                    if (current.submitMode == ParamEqSubmitMode.MANUAL) {
                        val sent = queuedSubmission ?: break
                        queuedSubmission = null
                        writeSnapshot(sent)
                        break // A manual click is exactly one transaction, never an automatic follow-up.
                    }
                    if (!current.phase.allowsEditing()) break
                    if (current.draft == current.transportBaseline) {
                        flushRequested = false
                        mutableState.value = current.copy(
                            phase = current.settledPhase,
                            canUndo = canUndo(current.settledPhase, current.isEditing),
                        )
                        break
                    }
                    if (!flushRequested) {
                        val remaining = lastWriteStart?.let { 150.milliseconds - it.elapsedNow() }
                        if (remaining != null && remaining.isPositive()) {
                            // Round upward: a sub-millisecond remainder must not start a write early.
                            val wholeMillis = remaining.inWholeMilliseconds
                            val waitMillis = wholeMillis + if (remaining > wholeMillis.milliseconds) 1L else 0L
                            withTimeoutOrNull(waitMillis.milliseconds) { wake.receive() }
                            continue // Re-read the latest draft, flush flag and monotonic clock.
                        }
                    }
                    writeSnapshot(current.draft)
                }
            }
        }

        private suspend fun load() {
            try {
                val actual = controls.getParamEq()
                if (closed) return
                mutableState.value = ParamEqEditState(
                    phase = ParamEqEditPhase.READY,
                    confirmed = actual,
                    draft = actual.bands.toList(),
                    submitMode = state.value.submitMode,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (closed) return
                val phase = if (error is DropException.UnsupportedCapability || error is DropException.UnsupportedDevice) {
                    ParamEqEditPhase.UNAVAILABLE
                } else {
                    ParamEqEditPhase.FAILED
                }
                mutableState.value = ParamEqEditState(phase, error = error, submitMode = state.value.submitMode)
                discardSignals()
            }
        }

        private suspend fun writeSnapshot(sent: List<GaiaPeqBand>) {
            val current = state.value
            val version = editVersion
            inflightVersion = version
            flushRequested = false
            lastWriteStart = timeSource.markNow()
            mutableState.value = current.copy(
                phase = ParamEqEditPhase.WRITING,
                confirmed = null,
                canUndo = canUndo(ParamEqEditPhase.WRITING, current.isEditing),
            )
            try {
                controls.setParamEq(sent)
                if (closed) return
                inflightVersion = null
                val latest = state.value
                val phase = if (editVersion == version || latest.draft == sent) {
                    ParamEqEditPhase.SENT
                } else {
                    ParamEqEditPhase.PENDING
                }
                // A successful transport never confirms device state or replaces a newer local draft.
                mutableState.value = latest.copy(
                    phase = phase,
                    lastSent = sent,
                    error = null,
                    canUndo = canUndo(phase, latest.isEditing),
                )
            } catch (error: Exception) {
                if (error is CancellationException) currentCoroutineContext().ensureActive()
                if (closed) return
                inflightVersion = null
                val latest = state.value
                mutableState.value = latest.copy(
                    phase = ParamEqEditPhase.FAILED,
                    confirmed = null,
                    lastSent = null,
                    error = error,
                    canUndo = false,
                )
                discardSignals()
            }
        }

        private fun discardSignals() {
            flushRequested = false
            queuedSubmission = null
            while (wake.tryReceive().isSuccess) { /* Discard wake-ups, not the retained failed draft. */ }
        }

        private fun canUndo(phase: ParamEqEditPhase, isEditing: Boolean): Boolean =
            undoSnapshot != null && !isEditing && phase.allowsEditing() &&
                (state.value.submitMode == ParamEqSubmitMode.REALTIME || phase != ParamEqEditPhase.WRITING)
    }

    private fun ParamEqEditState.allowsEditing(): Boolean =
        phase.allowsEditing() && (submitMode == ParamEqSubmitMode.REALTIME || phase != ParamEqEditPhase.WRITING)

    private fun ParamEqEditPhase.allowsEditing(): Boolean =
        this == ParamEqEditPhase.READY || this == ParamEqEditPhase.SENT ||
            this == ParamEqEditPhase.PENDING || this == ParamEqEditPhase.WRITING

    private val ParamEqEditState.transportBaseline: List<GaiaPeqBand>?
        get() = lastSent ?: confirmed?.bands

    private val ParamEqEditState.settledPhase: ParamEqEditPhase
        get() = if (lastSent != null) ParamEqEditPhase.SENT else ParamEqEditPhase.READY

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
                GaiaPeqParameters.gainRaw(value)
            }
            PeqParameter.Q -> {
                require(value >= 1.0 / 4096 && value <= 65535.0 / 4096)
                GaiaPeqParameters.qRaw(value).also { require(it in 1..65535) }
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
}
