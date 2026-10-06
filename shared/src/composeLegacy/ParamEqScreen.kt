package ink.lipoly.app.sunrise.composeLegacy

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ink.lipoly.app.sunrise.drop.GaiaPeqBand
import ink.lipoly.app.sunrise.drop.PeqFilter
import ink.lipoly.app.sunrise.drop.PeqHeadroom
import ink.lipoly.app.sunrise.catalog.*
import kotlin.math.roundToInt

private sealed interface PeqSheet {
    data object Bands : PeqSheet
    data class Filter(val index: Int) : PeqSheet
    data class Number(val editor: ParamEqEditor, val band: GaiaPeqBand, val parameter: PeqParameter, val returnFocus: FocusRequester) : PeqSheet
}

private fun filterName(filter: PeqFilter, english: Boolean): String = when (filter) {
    PeqFilter.PEAKING -> tr(english, "钟形 / 峰值", "Bell / Peaking")
    PeqFilter.LOW_SHELF -> tr(english, "低架", "Low shelf")
    PeqFilter.HIGH_SHELF -> tr(english, "高架", "High shelf")
    PeqFilter.LOW_PASS -> tr(english, "低通", "Low pass")
    PeqFilter.HIGH_PASS -> tr(english, "高通", "High pass")
    PeqFilter.BYPASS -> tr(english, "旁路", "Bypass")
}
private fun parameterName(parameter: PeqParameter, english: Boolean): String = when (parameter) {
    PeqParameter.GAIN -> tr(english, "增益", "Gain")
    PeqParameter.FREQUENCY -> tr(english, "频率", "Frequency")
    PeqParameter.Q -> "Q"
}
private fun parameterValue(band: GaiaPeqBand, parameter: PeqParameter): String = when (parameter) {
    PeqParameter.GAIN -> "${(band.gainDb * 100).roundToInt() / 100.0} dB"
    PeqParameter.FREQUENCY -> "${band.frequencyHz} Hz"
    PeqParameter.Q -> "Q ${(band.q * 1000).roundToInt() / 1000.0}"
}
private fun bandSummary(band: GaiaPeqBand, english: Boolean): String = "${band.frequencyHz} Hz · ${parameterValue(band, PeqParameter.GAIN)} · ${parameterValue(band, PeqParameter.Q)} · ${filterName(band.filter, english)}"
private fun canEdit(state: ParamEqEditState?): Boolean = when (state?.phase) {
    ParamEqEditPhase.READY, ParamEqEditPhase.SENT, ParamEqEditPhase.PENDING -> true
    ParamEqEditPhase.WRITING -> state.submitMode != ParamEqSubmitMode.MANUAL
    else -> false
}

@Composable
private fun ResponseLegend(
    label: String, alpha: Float, width: Dp, dashed: Boolean = false,
    lineColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
    val color = lineColor.copy(alpha = alpha)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(24.dp, 12.dp).drawWithCache {
            val effect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())) else null
            onDrawBehind { drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), width.toPx(), pathEffect = effect) }
        })
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ParamEqScreen(
    editor: ParamEqEditor?, state: ParamEqEditState?, english: Boolean, enabled: Boolean,
    referenceProduct: CatalogProduct?,
    referenceResponse: CatalogResponse?,
    showReferenceResponse: Boolean,
    includeResponsePreGain: Boolean,
    onChooseReference: () -> Unit,
    onResetReference: () -> Unit,
    onReferenceResponseChange: (Boolean) -> Unit,
    onResponsePreGainChange: (Boolean) -> Unit,
    referenceSource: String?,
    referenceRetrievedAt: String?,
    referenceResponseHash: String?,
    targetProduct: CatalogProduct?,
    targetResponse: CatalogResponse?,
    targetProductUuid: String?,
    targetResponseHash: String?,
    showTargetResponse: Boolean,
    onChooseTarget: () -> Unit,
    onClearTarget: () -> Unit,
    onTargetResponseChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    filterSelectionEnabled: Boolean = true,
) {
    var selectedIndex by remember(editor) { mutableStateOf(0) }
    var sheet by remember(editor) { mutableStateOf<PeqSheet?>(null) }
    var flattenDialog by remember(editor) { mutableStateOf(false) }
    var returnFocus by remember(editor) { mutableStateOf<FocusRequester?>(null) }
    var showReferenceDetails by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val bands = if (editor != null) state?.draft.orEmpty() else emptyList()
    val readyReference = (referenceResponse as? CatalogResponse.Ready)?.response
    // Hash is stable across selection/status/callback changes; FrequencyResponse has identity equality.
    val responseKey = referenceResponseHash ?: readyReference
    val overlay = remember(responseKey, bands, includeResponsePreGain, showReferenceResponse) {
        if (showReferenceResponse && readyReference != null)
            buildAcousticOverlay(readyReference, bands.takeIf { it.isNotEmpty() }, includeResponsePreGain)
        else null
    }
    val readyTarget = (targetResponse as? CatalogResponse.Ready)?.response
    val targetKey = targetResponseHash ?: readyTarget
    val sampledTarget = remember(targetKey) { readyTarget?.let { sampleCatalogResponse(it) } }
    val targetUnavailableReason = when {
        targetProductUuid == null -> null
        targetProduct == null -> tr(english, "所选目标已不在当前数据库中，请重新选择。", "The selected target is no longer in this database. Choose it again.")
        targetResponse is CatalogResponse.Unavailable -> tr(english, "目标频响无法解析：${targetResponse.reason}", "Target response unparseable: ${targetResponse.reason}")
        sampledTarget == null -> tr(english, "目录未提供可用的目标频响。", "The catalogue provides no usable target response.")
        sampledTarget.normalizationHz == null -> tr(english, "目标不覆盖 500 Hz，无法进行归一化形状对比。", "The target does not cover 500 Hz; normalized shape comparison is unavailable.")
        readyReference == null -> tr(english, "请先选择可用的源频响，才能进行形状对比。", "Choose a usable source response first to compare shapes.")
        readyReference.frequencyHz.first() > 500.0 || readyReference.frequencyHz.last() < 500.0 ->
            tr(english, "源频响不覆盖 500 Hz，无法进行归一化形状对比。", "The source does not cover 500 Hz; normalized shape comparison is unavailable.")
        else -> null
    }
    val target = sampledTarget.takeIf { showTargetResponse && targetProductUuid != null && targetUnavailableReason == null }
    val responseScale = remember(overlay, target) {
        if (target == null) overlay?.scale else acousticScale(overlay, target)
    }
    val hasOverlay = responseScale != null
    val preGainRaw = remember(bands, filterSelectionEnabled) {
        if (filterSelectionEnabled || bands.isEmpty()) null
        else try { PeqHeadroom.preGainRaw(bands) } catch (_: IllegalArgumentException) { null }
    }
    val editable = editor != null && enabled && canEdit(state)
    val maySelect = editable && state?.isEditing != true
    val manual = state?.submitMode == ParamEqSubmitMode.MANUAL
    val idle = state?.isEditing != true &&
        (state?.phase != ParamEqEditPhase.PENDING || manual) &&
        state?.phase != ParamEqEditPhase.WRITING && state?.phase != ParamEqEditPhase.LOADING
    val selected = bands.firstOrNull { it.index == selectedIndex }
    LaunchedEffect(editor, bands.size) {
        // A reload temporarily has no draft; retain the selected index until its new band count is known.
        if (bands.isNotEmpty() && bands.none { it.index == selectedIndex }) selectedIndex = bands.first().index
    }
    LaunchedEffect(editor, state?.phase, enabled) {
        if (!editable) {
            sheet = null
            flattenDialog = false
            returnFocus = null
            keyboard?.hide()
        }
    }
    LaunchedEffect(filterSelectionEnabled) {
        if (!filterSelectionEnabled && sheet is PeqSheet.Filter) sheet = null
    }
    LaunchedEffect(sheet) {
        if (sheet == null) {
            returnFocus?.let { focus -> runCatching { focus.requestFocus() } }
            returnFocus = null
        }
    }
    fun closeSheet() {
        returnFocus = (sheet as? PeqSheet.Number)?.returnFocus
        keyboard?.hide()
        focusManager.clearFocus()
        sheet = null
    }
    fun grouped(changed: GaiaPeqBand) {
        if (!editable || !canEdit(editor.state.value) || editor.state.value.isEditing) return
        val latest = editor.state.value.draft.firstOrNull { it.index == changed.index } ?: return
        if (changed == latest) return
        editor.beginEdit()
        editor.editBand(changed)
        editor.endEdit()
    }
    BoxWithConstraints(modifier) {
        val compact = maxWidth < 720.dp
        val curveHeight = if (compact) (maxHeight * 0.32f).coerceIn(180.dp, 240.dp) else (maxHeight * 0.4f).coerceAtLeast(260.dp)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(tr(english, "源频响", "Source response"), style = MaterialTheme.typography.titleMedium)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(referenceProduct?.let { "${it.name} · ${it.languageType ?: tr(english, "语言未提供", "Language unspecified")}" }
                                ?: tr(english, "未选择参考频响", "No reference response selected"), Modifier.weight(1f))
                            TextButton(onClick = { showReferenceDetails = !showReferenceDetails }) {
                                Text(tr(english, "资料", "Details"))
                            }
                        }
                        if (showReferenceDetails) {
                            referenceProduct?.let { Text("UUID: ${it.uuid}", style = MaterialTheme.typography.bodySmall) }
                            referenceSource?.let { Text(tr(english, "资料来源：$it", "Data source: $it"), style = MaterialTheme.typography.bodySmall) }
                            referenceRetrievedAt?.let { Text(tr(english, "快照时间：$it", "Snapshot time: $it"), style = MaterialTheme.typography.bodySmall) }
                        }
                        if (referenceResponse !is CatalogResponse.Ready) Text(
                            when (referenceResponse) {
                                is CatalogResponse.Unavailable -> tr(english, "参考频响无法解析：${referenceResponse.reason}", "Reference response unparseable: ${referenceResponse.reason}")
                                else -> tr(english, "目录未提供参考频响", "No reference response provided by the catalogue")
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onChooseReference) { Text(tr(english, "选择参考频响", "Choose reference response")) }
                            TextButton(onClick = onResetReference) { Text(tr(english, "恢复自动匹配", "Restore auto")) }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(tr(english, "显示源频响", "Show source response"), Modifier.weight(1f))
                            Switch(checked = showReferenceResponse, onCheckedChange = onReferenceResponseChange)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(tr(english, "预测含自动前置增益", "Include automatic pregain"), Modifier.weight(1f))
                            Switch(checked = includeResponsePreGain, onCheckedChange = onResponsePreGainChange)
                        }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(tr(english, "目标参考频响", "Target reference response"), style = MaterialTheme.typography.titleMedium)
                        Text(targetProduct?.let { "${it.name} · ${it.languageType ?: tr(english, "语言未提供", "Language unspecified")}" }
                            ?: if (targetProductUuid != null) tr(english, "所选目标不可用", "Selected target unavailable")
                            else tr(english, "未选择目标频响", "No target response selected"))
                        targetProductUuid?.let { Text("UUID: $it", style = MaterialTheme.typography.bodySmall) }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onChooseTarget) { Text(tr(english, "选择目标频响", "Choose target response")) }
                            TextButton(onClick = onClearTarget, enabled = targetProductUuid != null) { Text(tr(english, "清除目标", "Clear target")) }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(tr(english, "显示目标频响", "Show target response"), Modifier.weight(1f))
                            Switch(checked = showTargetResponse, onCheckedChange = onTargetResponseChange)
                        }
                        targetUnavailableReason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        Text(tr(english, "仅供手动调音参照，不自动匹配 EQ，不改变耳机配置。", "For manual tuning only. No automatic EQ fitting or changes to headset configuration."),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(when {
                        editor == null || state == null -> tr(english, "离线参考 · 不可编辑", "Offline reference · not editable")
                        state.isEditing -> tr(english, "调整中", "Editing")
                        else -> when (state.phase) {
                            ParamEqEditPhase.LOADING -> tr(english, "正在读取", "Loading")
                            ParamEqEditPhase.READY -> tr(english, "已读取设备配置", "Device configuration read")
                            ParamEqEditPhase.SENT -> tr(english, "已发送", "Sent")
                            ParamEqEditPhase.PENDING -> if (manual) tr(english, "草稿，尚未发送", "Draft, not sent") else tr(english, "待下发", "Pending")
                            ParamEqEditPhase.WRITING -> tr(english, "正在发送", "Sending")
                            ParamEqEditPhase.FAILED -> tr(english, "操作失败：需要重新读取", "Operation failed: reload required")
                            else -> tr(english, "不可用", "Unavailable")
                        }
                    }, style = MaterialTheme.typography.titleMedium)
                    if (editor != null && state != null) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(tr(english, "手动提交（诊断）", "Manual submission (diagnostic)"), Modifier.weight(1f))
                            Switch(
                                checked = manual,
                                onCheckedChange = { editor.setSubmitMode(if (it) ParamEqSubmitMode.MANUAL else ParamEqSubmitMode.REALTIME) },
                                enabled = enabled && state.canChangeSubmitMode,
                                modifier = Modifier.semantics { contentDescription = tr(english, "手动提交（诊断）", "Manual submission (diagnostic)") },
                            )
                        }
                        Text(
                            if (manual) tr(english, "编辑只保留草稿，松手不会发送。点击后提交一次完整配置，可能按 MTU 分包。", "Editing keeps a draft; release does not send. Submit sends one complete configuration, possibly in MTU-sized batches.")
                            else tr(english, "当前实时发送：编辑会自动写入耳机。", "Realtime submission: editing automatically writes to the headphones."),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (manual && !state.canChangeSubmitMode && state.phase == ParamEqEditPhase.PENDING && !state.isEditing) {
                            Text(tr(english, "先提交或重新读取草稿，再切换发送方式。", "Submit or reload the draft before changing submission mode."), style = MaterialTheme.typography.bodySmall)
                        }
                        if (manual) Button(
                            onClick = { editor.submit() },
                            enabled = enabled && state.canSubmit,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) { Text(tr(english, "提交一次", "Submit once")) }
                    }
                    if (!filterSelectionEnabled && bands.isNotEmpty()) {
                        if (preGainRaw != null) {
                            val db = (preGainRaw / 60.0 * 100).roundToInt() / 100.0
                            val label = if (state?.phase == ParamEqEditPhase.SENT)
                                tr(english, "已发送前置增益", "Sent pregain")
                            else tr(english, "自动前置增益（下次发送）", "Automatic pregain (next send)")
                            Text("$label：$db dB", style = MaterialTheme.typography.bodyMedium)
                        } else {
                            Text(tr(english, "无法计算可写入的峰值 EQ 前置增益；此配置不会发送。", "Cannot calculate writable peaking EQ headroom; this configuration will not be sent."),
                                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (state?.confirmed?.currentPreset != null && state.confirmed.currentPreset != 63) {
                        Text(if (manual) tr(english, "提交将切换至用户 EQ", "Submission will switch to User EQ") else tr(english, "编辑将切换至用户 EQ", "Editing will switch to User EQ"), style = MaterialTheme.typography.bodyMedium)
                    }
                    if (editor == null || state == null) Text(tr(english, "仅显示参考频响资料；连接支持 GAIA Bluetrum 参数 EQ 的设备后才能编辑。", "Reference response data only. Connect a GAIA Bluetrum parametric EQ device to edit."), style = MaterialTheme.typography.bodySmall)
                    else if (state.phase == ParamEqEditPhase.UNAVAILABLE && state.error == null) Text(tr(english, "设备未提供可编辑的 Bluetrum 用户 EQ", "The device does not expose an editable Bluetrum User EQ"))
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (hasOverlay) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (overlay != null) ResponseLegend(tr(english, "源频响", "Source response"), 0.65f, 1.5.dp, dashed = true)
                            if (overlay?.predictedDb != null) ResponseLegend(tr(english, "DSP 预测", "DSP prediction"), 1f, 2.dp)
                            if (target != null) ResponseLegend(tr(english, "目标参考", "Target reference"), 1f, 2.dp, dashed = true, lineColor = targetResponseColor)
                            if (bands.isNotEmpty()) ResponseLegend(tr(english, "EQ 响应", "EQ response"), 0.30f, 2.5.dp)
                        }
                    }
                    if (selected != null) Text(
                        "${tr(english, "第 ${selected.index + 1} 段", "Band ${selected.index + 1}")} · ${parameterValue(selected, PeqParameter.Q)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    ParamEqCurve(bands, selectedIndex, editor, editable, english, compact,
                        onSelect = { if (maySelect && !editor.state.value.isEditing) selectedIndex = it },
                        modifier = Modifier.fillMaxWidth().height(curveHeight), overlay = overlay, target = target, responseScale = responseScale)
                    if (editable && bands.isNotEmpty()) Text(
                        tr(english, "先选频段；单指拖动调频率/增益，双指横向张开调宽、合拢调窄（Q）。",
                            "Select a band. Drag one finger for frequency/gain; spread two fingers horizontally to widen, pinch to narrow (Q)."),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (target != null) {
                        Text(tr(english, "源频响与目标各自以 500 Hz=0 dB 归一化；源频响、预测和目标共用右轴，仅对比调音形状，各自只画资料覆盖范围。",
                            "Source and target are each normalized at 500 Hz=0 dB. Source, prediction and target share the right axis for shape comparison; each uses only its data coverage."),
                            style = MaterialTheme.typography.bodySmall)
                        Text(tr(english, "测量条件、佩戴与音量可能不同，曲线接近不保证听感相同。比较形状时可关闭“预测含自动前置增益”，避免整体音量偏移干扰。",
                            "Measurement conditions, fit and volume may differ; similar curves do not guarantee the same sound. Exclude automatic pregain when comparing shapes to avoid an overall level offset."),
                            style = MaterialTheme.typography.bodySmall)
                        if (!hasOverlay) Text(tr(english, "目标及其他频响数值超出可显示范围。", "Target and other response values exceed the displayable range."),
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (overlay != null) {
                        if (!hasOverlay) Text(
                            if (overlay.frequencyHz.isEmpty()) tr(english, "参考在 20–20000 Hz 内没有可显示频段。", "Reference has no displayable frequency range within 20–20000 Hz.")
                            else tr(english, "参考及预测数值超出可显示范围。", "Reference and prediction values exceed the displayable range."),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        else {
                            Text(
                                if (overlay.normalizationHz != null)
                                    tr(english, "SunRise 显示归一化：500 Hz=0 dB；仅绘制资料覆盖范围，左轴用于 EQ 编辑，右轴用于参考。", "SunRise display normalization: 500 Hz=0 dB. Only the data's covered range is drawn; left axis edits EQ, right axis shows reference.")
                                else tr(english, "资料不覆盖 500 Hz：显示未归一化原始 SPL；仅绘制资料覆盖范围，左轴用于 EQ 编辑，右轴用于参考。", "Data does not cover 500 Hz: unnormalized raw SPL is shown. Only the covered range is drawn; left axis edits EQ, right axis shows reference."),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        overlay.predictionError?.let { Text(tr(english, "预测不可用：$it", "Prediction unavailable: $it"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        if (overlay.predictedDb != null) Text(
                            tr(english, "参考 + 当前 EQ（48 kHz RBJ，默认含自动前置增益）；${if (includeResponsePreGain) "当前前置增益 ${overlay.includedPreGainRaw?.div(60.0)} dB" else "当前已关闭前置增益"}。这是当前草稿的写入模型预测，已发送不等于已应用或实测。", "Reference + current EQ (48 kHz RBJ, automatic pregain included by default); ${if (includeResponsePreGain) "current pregain ${overlay.includedPreGainRaw?.div(60.0)} dB" else "pregain currently excluded"}. This is the current draft's write-model prediction; sent does not mean applied or measured."),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (referenceProduct != null) Text(
                        tr(english, "参考频响来自产品目录或频响库，测量资料可能已含调音，目标曲线并非设备实测；不代表当前耳机实测，不保证不同 ANC、佩戴或音量状态一致，不能作为绝对声压。", "Reference responses come from the product catalog or response library. Measurement data may already include tuning; target curves are not device measurements. They do not measure the connected headset; ANC, fit and volume may differ. This is not absolute sound pressure."),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (bands.isNotEmpty()) {
                item {
                    BandSelector(bands, selectedIndex, maySelect, english) { if (editor?.state?.value?.isEditing != true) selectedIndex = it }
                }
                if (compact) item {
                    OutlinedButton(onClick = { sheet = PeqSheet.Bands }, enabled = maySelect, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(tr(english, "全部频段", "All bands"))
                    }
                }
            }
            // Stable selected controls stay below the graph: nothing floats under a moving finger.
            if (selected != null && editor != null) item {
                ParamEqBandCard(selected, editor, state, editable, english,
                    filterSelectionEnabled = filterSelectionEnabled,
                    onNumber = { parameter, requester ->
                        if (maySelect && !editor.state.value.isEditing) editor.state.value.draft.firstOrNull { it.index == selected.index }?.let {
                            sheet = PeqSheet.Number(editor, it, parameter, requester)
                        }
                    },
                    onFilter = { if (filterSelectionEnabled && maySelect && !editor.state.value.isEditing) sheet = PeqSheet.Filter(selected.index) },
                    onNudge = { parameter, direction -> editor.state.value.draft.firstOrNull { it.index == selected.index }?.let { grouped(peqNudge(it, parameter, direction)) } })
            }
            if (editor != null) item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state?.error?.let { error ->
                        Text(errorMessage(error, english), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (state?.phase == ParamEqEditPhase.FAILED && state.confirmed == null) {
                        Text(tr(english, "设备当前配置未知，写入可能已部分生效。", "The device configuration is unknown; a write may have partially applied."), color = MaterialTheme.colorScheme.error)
                    }
                    OutlinedButton(onClick = { editor.refresh() }, enabled = enabled && idle,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(if (manual && state.phase == ParamEqEditPhase.PENDING) tr(english, "重新读取（丢弃草稿）", "Reload (discard draft)") else tr(english, "重新读取", "Reload"))
                    }
                    OutlinedButton(onClick = { editor.undo() }, enabled = editable && state?.canUndo == true && !state.isEditing,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(tr(english, "撤销", "Undo")) }
                    OutlinedButton(onClick = { editor.state.value.draft.firstOrNull { it.index == selectedIndex }?.let { grouped(peqReset(it)) } }, enabled = maySelect && selected != null,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(tr(english, "重置当前段", "Reset band")) }
                    TextButton(onClick = { flattenDialog = true }, enabled = maySelect && bands.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(tr(english, "平直…", "Flatten…")) }
                }
            }
            if (!compact && bands.isNotEmpty()) {
                item { Text(tr(english, "全部频段", "All bands"), style = MaterialTheme.typography.titleMedium) }
                items(bands, key = { it.index }) { band ->
                    // Wide layouts expose every raw-preserving parameter entry, using the same sheets and groups.
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { selectedIndex = band.index }, enabled = maySelect,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { stateDescription = if (band.index == selectedIndex) tr(english, "已选中", "Selected") else tr(english, "未选中", "Not selected") }) {
                                Text(tr(english, "第 ${band.index + 1} 段", "Band ${band.index + 1}"))
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                PeqParameter.entries.forEach { parameter ->
                                    val requester = remember(editor, band.index, parameter) { FocusRequester() }
                                    OutlinedButton(onClick = { sheet = PeqSheet.Number(editor!!, band, parameter, requester) }, enabled = maySelect && (parameter != PeqParameter.GAIN || band.filter !in listOf(PeqFilter.LOW_PASS, PeqFilter.HIGH_PASS)),
                                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).focusRequester(requester).semantics { contentDescription = tr(english, "第 ${band.index + 1} 段 ${parameterName(parameter, english)}", "Band ${band.index + 1} ${parameterName(parameter, english)}"); stateDescription = parameterValue(band, parameter) }) {
                                        Text("${parameterName(parameter, english)}\n${parameterValue(band, parameter)}")
                                    }
                                }
                            }
                            OutlinedButton(onClick = { sheet = PeqSheet.Filter(band.index) }, enabled = filterSelectionEnabled && maySelect, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(filterName(band.filter, english)) }
                        }
                    }
                }
            }
            item {
                if (bands.isNotEmpty()) Text(tr(english, "左轴 EQ 曲线为 48 kHz 参数响应估算，不含自动前置衰减，非耳机实测。", "The left-axis EQ curve is a 48 kHz parameter-response estimate without automatic pregain, not a headphone measurement."), style = MaterialTheme.typography.bodySmall)
                if (bands.isNotEmpty() && !filterSelectionEnabled) Text(tr(english, "Bluetrum 验证模式：固定峰值，写入类型码 0。", "Bluetrum verification mode: peaking only, wire type 0."), style = MaterialTheme.typography.bodySmall)
                if (!compact && bands.isNotEmpty()) Text(tr(english, "鼠标：拖节点；Shift 锁轴；Q 手柄 / 滚轮；双击重置。方向键细调，Shift 大步、Ctrl 精细。", "Mouse: drag nodes; Shift locks an axis; Q handles / wheel; double-click resets. Arrow keys adjust; Shift is coarse, Ctrl fine."), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (flattenDialog) {
        AlertDialog(onDismissRequest = { flattenDialog = false }, title = { Text(tr(english, "平直所有频段？", "Flatten all bands?")) },
            text = { Text(if (manual)
                tr(english, "将所有频段草稿设为平直，不发送到耳机；需要点击“提交一次”。这不是恢复出厂或保存到 Flash。", "Set the draft bands flat without sending to the headphones; use Submit once to send. This is not a factory reset or a Flash save.")
                else tr(english, "将所有频段设为平直并发送到耳机。这不是恢复出厂或保存到 Flash。", "Set all bands flat and send them to the headphones. This is not a factory reset or a Flash save.")) },
            confirmButton = { TextButton(enabled = maySelect, onClick = {
                flattenDialog = false
                if (maySelect) {
                    val latest = editor.state.value.draft
                    val changed = peqFlatten(latest)
                    if (changed != latest) { editor.beginEdit(); editor.replaceDraft(changed); editor.endEdit() }
                }
            }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr(english, "确认", "Confirm")) } },
            dismissButton = { TextButton(onClick = { flattenDialog = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr(english, "取消", "Cancel")) } })
    }
    val currentSheet = sheet
    if (currentSheet != null && editable && (filterSelectionEnabled || currentSheet !is PeqSheet.Filter)) {
        ModalBottomSheet(
            onDismissRequest = { closeSheet() },
            sheetState = rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
            ),
        ) {
            when (currentSheet) {
                PeqSheet.Bands -> {
                    Text(tr(english, "全部频段", "All bands"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 600.dp).navigationBarsPadding()) {
                        items(bands, key = { it.index }) { band ->
                            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(selected = band.index == selectedIndex, enabled = maySelect, role = Role.RadioButton,
                                onClick = { selectedIndex = band.index; closeSheet() }).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = band.index == selectedIndex, onClick = null, enabled = maySelect)
                                Column(Modifier.padding(start = 12.dp)) { Text(tr(english, "第 ${band.index + 1} 段", "Band ${band.index + 1}")); Text(bandSummary(band, english), style = MaterialTheme.typography.bodyMedium) }
                            }
                        }
                    }
                }
                is PeqSheet.Filter -> {
                    val band = bands.firstOrNull { it.index == currentSheet.index }
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(16.dp).selectableGroup()) {
                        Text(tr(english, "第 ${currentSheet.index + 1} 段 · 滤波器", "Band ${currentSheet.index + 1} · Filter"), style = MaterialTheme.typography.titleLarge)
                        listOf(PeqFilter.PEAKING, PeqFilter.LOW_SHELF, PeqFilter.HIGH_SHELF, PeqFilter.LOW_PASS, PeqFilter.HIGH_PASS, PeqFilter.BYPASS).forEach { filter ->
                            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(selected = band?.filter == filter, enabled = maySelect, role = Role.RadioButton,
                                onClick = { if (band != null) grouped(peqEnableFilter(band, filter)); closeSheet() }), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = band?.filter == filter, onClick = null, enabled = maySelect)
                                Text(filterName(filter, english), modifier = Modifier.padding(start = 12.dp))
                            }
                        }
                        TextButton(onClick = { closeSheet() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(tr(english, "取消", "Cancel")) }
                    }
                }
                is PeqSheet.Number -> key(currentSheet) {
                    PeqNumberSheet(currentSheet.band, currentSheet.parameter, english, onCancel = { closeSheet() }, onConfirm = { raw ->
                        if (currentSheet.editor === editor && maySelect) {
                            // Resolve the current complete draft; replace this field only, never the stale opening band.
                            currentSheet.editor.state.value.draft.firstOrNull { it.index == currentSheet.band.index }?.let { latest ->
                                if (raw != null) grouped(peqWithRaw(latest, currentSheet.parameter, raw))
                            }
                        }
                        closeSheet()
                    })
                }
            }
        }
    }
}

@Composable
private fun BandSelector(bands: List<GaiaPeqBand>, selectedIndex: Int, enabled: Boolean, english: Boolean, onSelect: (Int) -> Unit) {
    val scroll = rememberScrollState()
    var viewport by remember { mutableStateOf(0f) }
    var selectedLeft by remember { mutableStateOf(0f) }
    var selectedRight by remember { mutableStateOf(0f) }
    LaunchedEffect(selectedIndex, selectedLeft, selectedRight, viewport) {
        val left = selectedLeft
        val right = selectedRight
        if (left < scroll.value) scroll.animateScrollTo(left.toInt().coerceAtLeast(0))
        else if (right > scroll.value + viewport) scroll.animateScrollTo((right - viewport).toInt().coerceAtLeast(0))
    }
    Row(Modifier.fillMaxWidth().onGloballyPositioned { viewport = it.size.width.toFloat() }.horizontalScroll(scroll).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        bands.forEach { band ->
            FilterChip(selected = band.index == selectedIndex, enabled = enabled,
                onClick = { onSelect(band.index) },
                modifier = Modifier.heightIn(min = 48.dp).onGloballyPositioned {
                    if (band.index == selectedIndex) {
                        val bounds = it.boundsInParent()
                        selectedLeft = bounds.left
                        selectedRight = bounds.right
                    }
                }.semantics { contentDescription = tr(english, "第 ${band.index + 1} 段，${band.frequencyHz} Hz", "Band ${band.index + 1}, ${band.frequencyHz} Hz") },
                label = { Text("${band.index + 1} · ${band.frequencyHz} Hz") })
        }
    }
}

@Composable
private fun ParamEqBandCard(
    band: GaiaPeqBand, editor: ParamEqEditor, state: ParamEqEditState?, enabled: Boolean, english: Boolean,
    filterSelectionEnabled: Boolean,
    onNumber: (PeqParameter, FocusRequester) -> Unit, onFilter: () -> Unit, onNudge: (PeqParameter, Int) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr(english, "第 ${band.index + 1} 段", "Band ${band.index + 1}"), style = MaterialTheme.typography.titleMedium)
            if (band.filter == PeqFilter.BYPASS) Text(tr(english, "旁路：保留原始数值。选择其他滤波器后启用参数。", "Bypassed: raw values are preserved. Choose another filter to enable parameters."))
            PeqParameter.entries.forEach { parameter ->
                PeqParameterControl(band, parameter, editor, state, enabled, english, onNumber, onNudge)
            }
            OutlinedButton(onClick = onFilter, enabled = filterSelectionEnabled && enabled && state?.isEditing != true,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = tr(english, "第 ${band.index + 1} 段滤波器", "Band ${band.index + 1} filter"); stateDescription = filterName(band.filter, english) }) { Text(filterName(band.filter, english)) }
        }
    }
}

@Composable
private fun PeqParameterControl(
    band: GaiaPeqBand, parameter: PeqParameter, editor: ParamEqEditor, state: ParamEqEditState?, enabled: Boolean, english: Boolean,
    onNumber: (PeqParameter, FocusRequester) -> Unit, onNudge: (PeqParameter, Int) -> Unit,
) {
    val requester = remember(editor, band.index, parameter) { FocusRequester() }
    var sliderGroup by remember(editor, band.index, parameter) { mutableStateOf(false) }
    val latestBand by rememberUpdatedState(band)
    val name = parameterName(parameter, english)
    val description = tr(english, "第 ${band.index + 1} 段 $name", "Band ${band.index + 1} $name")
    val inRange = peqSliderInRange(band, parameter)
    val applicable = band.filter != PeqFilter.BYPASS && (parameter != PeqParameter.GAIN || peqHasGain(band.filter))
    val sliderEnabled = enabled && applicable && inRange && (state?.isEditing != true || sliderGroup)
    fun finish() { if (sliderGroup) { sliderGroup = false; editor.endEdit() } }
    DisposableEffect(editor, band.index, parameter) { onDispose { finish() } }
    LaunchedEffect(enabled) { if (!enabled) finish() }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // A stacked label/value layout remains usable at fontScale 2 without shrinking text.
        Text(name, style = MaterialTheme.typography.labelLarge)
        OutlinedButton(onClick = { onNumber(parameter, requester) }, enabled = enabled && state?.isEditing != true && (parameter != PeqParameter.GAIN || band.filter !in listOf(PeqFilter.LOW_PASS, PeqFilter.HIGH_PASS)),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).focusRequester(requester).semantics { contentDescription = description; stateDescription = parameterValue(band, parameter) }) {
            Text(parameterValue(band, parameter))
        }
        if (!inRange && applicable) Text(tr(english, "超出滑杆范围，点数值编辑", "Outside slider range; tap the value to edit"), style = MaterialTheme.typography.bodySmall)
        val value = when (parameter) {
            PeqParameter.GAIN -> peqGainFraction(band.gainRaw).coerceIn(0f, 1f)
            PeqParameter.FREQUENCY -> peqFrequencyFraction(band.frequencyHz)
            PeqParameter.Q -> peqQFraction(band.qRaw)
        }
        Box(Modifier.fillMaxWidth().heightIn(min = 56.dp).pointerInput(editor, band.index, parameter) {
            // Observe, without consuming, so Material Slider owns input; a cancelled pointer still ends its group.
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.firstOrNull { it.id == down.id }?.pressed != true) break
                    }
                } finally { finish() }
            }
        }, contentAlignment = Alignment.Center) {
            Slider(value = value, enabled = sliderEnabled, steps = if (parameter == PeqParameter.GAIN) 239 else 0,
                onValueChange = { fraction ->
                    val raw = when (parameter) {
                        PeqParameter.GAIN -> peqGainAt(fraction)
                        PeqParameter.FREQUENCY -> peqFrequencyAt(fraction)
                        PeqParameter.Q -> peqQAt(fraction)
                    }
                    val current = editor.state.value.draft.firstOrNull { it.index == latestBand.index }
                    if (current != null && sliderEnabled && raw != peqRaw(current, parameter)) {
                        if (!sliderGroup) { editor.beginEdit(); sliderGroup = true }
                        editor.editBand(peqWithRaw(current, parameter, raw))
                    }
                }, onValueChangeFinished = { finish() },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics { contentDescription = description; stateDescription = parameterValue(band, parameter) })
        }
        if (parameter == PeqParameter.GAIN) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("−12 dB", style = MaterialTheme.typography.bodySmall)
            Text("0 dB", style = MaterialTheme.typography.bodySmall)
            Text("+12 dB", style = MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { onNudge(parameter, -1) }, enabled = sliderEnabled && state?.isEditing != true,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = tr(english, "$description 减小", "Decrease $description") }) { Text("−") }
            OutlinedButton(onClick = { onNudge(parameter, 1) }, enabled = sliderEnabled && state?.isEditing != true,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = tr(english, "$description 增大", "Increase $description") }) { Text("+") }
        }
    }
}

@Composable
private fun PeqNumberSheet(band: GaiaPeqBand, parameter: PeqParameter, english: Boolean, onCancel: () -> Unit, onConfirm: (Int?) -> Unit) {
    val original = remember(band, parameter) { peqInputText(band, parameter) }
    var text by remember { mutableStateOf(original) }
    var invalid by remember { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    fun confirm() {
        val parsed = runCatching { peqParseInput(text, band, parameter) }
        if (parsed.isFailure) { invalid = true; return }
        val raw = parsed.getOrThrow()
        onConfirm(if (text == original || raw == peqRaw(band, parameter)) null else raw)
    }
    LaunchedEffect(Unit) { requester.requestFocus() }
    Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(tr(english, "第 ${band.index + 1} 段 · ${parameterName(parameter, english)}", "Band ${band.index + 1} · ${parameterName(parameter, english)}"), style = MaterialTheme.typography.titleLarge)
        Text(when (parameter) {
            PeqParameter.FREQUENCY -> tr(english, "20–20000 Hz，整数", "20–20000 Hz, integers")
            PeqParameter.GAIN -> "−12–+12 dB"
            PeqParameter.Q -> "Q ${1.0 / 4096}–${65535.0 / 4096}"
        })
        OutlinedTextField(value = text, onValueChange = { text = it; invalid = false }, singleLine = true, isError = invalid,
            label = { Text(parameterName(parameter, english)) },
            keyboardOptions = KeyboardOptions(keyboardType = if (parameter == PeqParameter.FREQUENCY) KeyboardType.Number else KeyboardType.Decimal, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { confirm() }),
            modifier = Modifier.fillMaxWidth().focusRequester(requester).semantics { contentDescription = tr(english, "第 ${band.index + 1} 段 ${parameterName(parameter, english)}", "Band ${band.index + 1} ${parameterName(parameter, english)}") })
        if (parameter == PeqParameter.GAIN) OutlinedButton(onClick = {
            text = if (text.trim().startsWith('-')) text.trim().removePrefix("-") else "-${text.trim().removePrefix("+")}"
            invalid = false
        }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(tr(english, "切换正 / 负号", "Toggle positive / negative")) }
        if (invalid) Text(tr(english, "请输入范围内的有效数值。只允许一个小数点或逗号，不支持千位分组。", "Enter a valid value in range. Use one decimal dot or comma, without digit grouping."), color = MaterialTheme.colorScheme.error)
        Button(onClick = { confirm() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(tr(english, "确认", "Confirm")) }
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(tr(english, "取消", "Cancel")) }
    }
}
