package ink.lipoly.app.sunrise.compose.entries

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
import ink.lipoly.app.sunrise.drop.AncMode
import ink.lipoly.app.sunrise.drop.DropException
import kotlin.math.roundToInt
import ink.lipoly.app.sunrise.settings.UiSettings
import ink.lipoly.app.sunrise.compose.FancyEqualizer.ParamEqCurve
import ink.lipoly.app.sunrise.compose.FancyEqualizer.ParamEqEditPhase
import ink.lipoly.app.sunrise.compose.FancyEqualizer.ParamEqEditState
import ink.lipoly.app.sunrise.compose.FancyEqualizer.ParamEqEditor
import ink.lipoly.app.sunrise.compose.FancyEqualizer.ParamEqSubmitMode
import ink.lipoly.app.sunrise.compose.FancyEqualizer.PeqParameter
import ink.lipoly.app.sunrise.compose.FancyEqualizer.peqFlatten
import ink.lipoly.app.sunrise.compose.FancyEqualizer.peqFrequencyAt
import ink.lipoly.app.sunrise.compose.FancyEqualizer.peqFrequencyFraction
import ink.lipoly.app.sunrise.compose.FancyEqualizer.peqGainAt
import ink.lipoly.app.sunrise.compose.FancyEqualizer.peqGainFraction
import ink.lipoly.app.sunrise.compose.FancyEqualizer.peqHasGain
import ink.lipoly.app.sunrise.compose.FancyEqualizer.peqInputText
import ink.lipoly.app.sunrise.compose.FancyEqualizer.peqNudge
import ink.lipoly.app.sunrise.compose.FancyEqualizer.peqParseInput
import ink.lipoly.app.sunrise.compose.FancyEqualizer.peqQAt
import ink.lipoly.app.sunrise.compose.FancyEqualizer.peqQFraction
import ink.lipoly.app.sunrise.compose.FancyEqualizer.peqRaw
import ink.lipoly.app.sunrise.compose.FancyEqualizer.peqReset
import ink.lipoly.app.sunrise.compose.FancyEqualizer.peqSliderInRange
import ink.lipoly.app.sunrise.compose.FancyEqualizer.peqWithRaw
import ink.lipoly.app.sunrise.compose.FancyEqualizer.targetResponseColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EqualizerEntry(
    editor: ParamEqEditor?,
    state: ParamEqEditState?,
    enabled: Boolean,
    snapshot: CatalogSnapshot?,
    reference: CatalogReferenceSelection,
    settings: UiSettings,
    onChooseReference: () -> Unit,
    onResetReference: () -> Unit,
    onChooseTarget: () -> Unit,
    onClearTarget: () -> Unit,
    onSettingsChange: (UiSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    val referenceProduct = reference.product
    val referenceResponse = reference.response
    val referenceSource = snapshot?.let { current -> reference.product?.let { catalogSourceUrl(current, it) } }
    val referenceRetrievedAt = snapshot?.retrievedAt
    val referenceResponseHash = reference.product?.freqResponse?.let { snapshot?.responseHashesByPath?.get(it) }
    val showReferenceResponse = settings.showReferenceResponse
    val includeResponsePreGain = settings.includeResponsePreGain
    val targetProductUuid = settings.targetProductUuid
    val showTargetResponse = settings.showTargetResponse
    val targetProduct = settings.targetProductUuid?.let { snapshot?.productsByUuid?.get(it) }
    val targetResponse = targetProduct?.freqResponse?.let { snapshot?.responsesByPath?.get(it) }
    val targetResponseHash = targetProduct?.freqResponse?.let { snapshot?.responseHashesByPath?.get(it) }
    val onTargetResponseChange: (Boolean) -> Unit = { onSettingsChange(settings.copy(showTargetResponse = it)) }
    val onReferenceResponseChange: (Boolean) -> Unit = { onSettingsChange(settings.copy(showReferenceResponse = it)) }
    val onResponsePreGainChange: (Boolean) -> Unit = { onSettingsChange(settings.copy(includeResponsePreGain = it)) }
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
        targetProduct == null -> "所选目标已不在当前数据库中，请重新选择。"
        targetResponse is CatalogResponse.Unavailable -> "目标频响无法解析：${targetResponse.reason}"
        sampledTarget == null -> "目录未提供可用的目标频响。"
        sampledTarget.normalizationHz == null -> "目标不覆盖 500 Hz，无法进行归一化形状对比。"
        readyReference == null -> "请先选择可用的源频响，才能进行形状对比。"
        readyReference.frequencyHz.first() > 500.0 || readyReference.frequencyHz.last() < 500.0 ->
            "源频响不覆盖 500 Hz，无法进行归一化形状对比。"
        else -> null
    }
    val target = sampledTarget.takeIf { showTargetResponse && targetProductUuid != null && targetUnavailableReason == null }
    val responseScale = remember(overlay, target) {
        if (target == null) overlay?.scale else acousticScale(overlay, target)
    }
    val hasOverlay = responseScale != null
    val preGainRaw = remember(bands) {
        if (bands.isEmpty()) null
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
                        Text("源频响", style = MaterialTheme.typography.titleMedium)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(referenceProduct?.let { "${it.name} · ${it.languageType ?: "语言未提供"}" }
                                ?: "未选择参考频响", Modifier.weight(1f))
                            TextButton(onClick = { showReferenceDetails = !showReferenceDetails }) {
                                Text("资料")
                            }
                        }
                        if (showReferenceDetails) {
                            referenceProduct?.let { Text("UUID: ${it.uuid}", style = MaterialTheme.typography.bodySmall) }
                            referenceSource?.let { Text("资料来源：$it", style = MaterialTheme.typography.bodySmall) }
                            referenceRetrievedAt?.let { Text("快照时间：$it", style = MaterialTheme.typography.bodySmall) }
                        }
                        if (referenceResponse !is CatalogResponse.Ready) Text(
                            when (referenceResponse) {
                                is CatalogResponse.Unavailable -> "参考频响无法解析：${referenceResponse.reason}"
                                else -> "目录未提供参考频响"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onChooseReference) { Text("选择参考频响") }
                            TextButton(onClick = onResetReference) { Text("恢复自动匹配") }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("显示源频响", Modifier.weight(1f))
                            Switch(checked = showReferenceResponse, onCheckedChange = onReferenceResponseChange)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("预测含自动前置增益", Modifier.weight(1f))
                            Switch(checked = includeResponsePreGain, onCheckedChange = onResponsePreGainChange)
                        }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("目标参考频响", style = MaterialTheme.typography.titleMedium)
                        Text(targetProduct?.let { "${it.name} · ${it.languageType ?: "语言未提供"}" }
                            ?: if (targetProductUuid != null) "所选目标不可用"
                            else "未选择目标频响")
                        targetProductUuid?.let { Text("UUID: $it", style = MaterialTheme.typography.bodySmall) }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onChooseTarget) { Text("选择目标频响") }
                            TextButton(onClick = onClearTarget, enabled = targetProductUuid != null) { Text("清除目标") }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("显示目标频响", Modifier.weight(1f))
                            Switch(checked = showTargetResponse, onCheckedChange = onTargetResponseChange)
                        }
                        targetUnavailableReason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        Text("仅供手动调音参照，不自动匹配 EQ，不改变耳机配置。",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(when {
                        editor == null || state == null -> "离线参考 · 不可编辑"
                        state.isEditing -> "调整中"
                        else -> when (state.phase) {
                            ParamEqEditPhase.LOADING -> "正在读取"
                            ParamEqEditPhase.READY -> "已读取设备配置"
                            ParamEqEditPhase.SENT -> "已发送"
                            ParamEqEditPhase.PENDING -> if (manual) "草稿，尚未发送" else "待下发"
                            ParamEqEditPhase.WRITING -> "正在发送"
                            ParamEqEditPhase.FAILED -> "操作失败：需要重新读取"
                            else -> "不可用"
                        }
                    }, style = MaterialTheme.typography.titleMedium)
                    if (editor != null && state != null) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("手动提交（诊断）", Modifier.weight(1f))
                            Switch(
                                checked = manual,
                                onCheckedChange = { editor.setSubmitMode(if (it) ParamEqSubmitMode.MANUAL else ParamEqSubmitMode.REALTIME) },
                                enabled = enabled && state.canChangeSubmitMode,
                                modifier = Modifier.semantics { contentDescription = "手动提交（诊断）" },
                            )
                        }
                        Text(
                            if (manual) "编辑只保留草稿，松手不会发送。点击后提交一次完整配置，可能按 MTU 分包。"
                            else "当前实时发送：编辑会自动写入耳机。",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (manual && !state.canChangeSubmitMode && state.phase == ParamEqEditPhase.PENDING && !state.isEditing) {
                            Text("先提交或重新读取草稿，再切换发送方式。", style = MaterialTheme.typography.bodySmall)
                        }
                        if (manual) Button(
                            onClick = { editor.submit() },
                            enabled = enabled && state.canSubmit,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) { Text("提交一次") }
                    }
                    if (bands.isNotEmpty()) {
                        if (preGainRaw != null) {
                            val db = (preGainRaw / 60.0 * 100).roundToInt() / 100.0
                            val label = if (state?.phase == ParamEqEditPhase.SENT)
                                "已发送前置增益"
                            else "自动前置增益（下次发送）"
                            Text("$label：$db dB", style = MaterialTheme.typography.bodyMedium)
                        } else {
                            Text("无法计算可写入的峰值 EQ 前置增益；此配置不会发送。",
                                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (state?.confirmed?.currentPreset != null && state.confirmed.currentPreset != 63) {
                        Text(if (manual) "提交将切换至用户 EQ" else "编辑将切换至用户 EQ", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (editor == null || state == null) Text("仅显示参考频响资料；连接支持 GAIA Bluetrum 参数 EQ 的设备后才能编辑。", style = MaterialTheme.typography.bodySmall)
                    else if (state.phase == ParamEqEditPhase.UNAVAILABLE && state.error == null) Text("设备未提供可编辑的 Bluetrum 用户 EQ")
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (hasOverlay) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (overlay != null) ResponseLegend("源频响", 0.65f, 1.5.dp, dashed = true)
                            if (overlay?.predictedDb != null) ResponseLegend("DSP 预测", 1f, 2.dp)
                            if (target != null) ResponseLegend("目标参考", 1f, 2.dp, dashed = true, lineColor = targetResponseColor)
                            if (bands.isNotEmpty()) ResponseLegend("EQ 响应", 0.30f, 2.5.dp)
                        }
                    }
                    if (selected != null) Text(
                        "第 ${selected.index + 1} 段 · ${parameterValue(selected, PeqParameter.Q)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    ParamEqCurve(bands, selectedIndex, editor, editable, compact,
                        onSelect = { if (maySelect && !editor.state.value.isEditing) selectedIndex = it },
                        modifier = Modifier.fillMaxWidth().height(curveHeight), overlay = overlay, target = target, responseScale = responseScale)
                    if (editable && bands.isNotEmpty()) Text(
                        "先选频段；单指拖动调频率/增益，双指横向张开调宽、合拢调窄（Q）。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (target != null) {
                        Text("源频响与目标各自以 500 Hz=0 dB 归一化；源频响、预测和目标共用右轴，仅对比调音形状，各自只画资料覆盖范围。",
                            style = MaterialTheme.typography.bodySmall)
                        Text("测量条件、佩戴与音量可能不同，曲线接近不保证听感相同。比较形状时可关闭“预测含自动前置增益”，避免整体音量偏移干扰。",
                            style = MaterialTheme.typography.bodySmall)
                        if (!hasOverlay) Text("目标及其他频响数值超出可显示范围。",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (overlay != null) {
                        if (!hasOverlay) Text(
                            if (overlay.frequencyHz.isEmpty()) "参考在 20–20000 Hz 内没有可显示频段。"
                            else "参考及预测数值超出可显示范围。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        else {
                            Text(
                                if (overlay.normalizationHz != null)
                                    "SunRise 显示归一化：500 Hz=0 dB；仅绘制资料覆盖范围，左轴用于 EQ 编辑，右轴用于参考。"
                                else "资料不覆盖 500 Hz：显示未归一化原始 SPL；仅绘制资料覆盖范围，左轴用于 EQ 编辑，右轴用于参考。",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        overlay.predictionError?.let { Text("预测不可用：$it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        if (overlay.predictedDb != null) Text(
                            "参考 + 当前 EQ（48 kHz RBJ，默认含自动前置增益）；${if (includeResponsePreGain) "当前前置增益 ${overlay.includedPreGainRaw?.div(60.0)} dB" else "当前已关闭前置增益"}。这是当前草稿的写入模型预测，已发送不等于已应用或实测。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (referenceProduct != null) Text(
                        "参考频响来自产品目录或频响库，测量资料可能已含调音，目标曲线并非设备实测；不代表当前耳机实测，不保证不同 ANC、佩戴或音量状态一致，不能作为绝对声压。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (bands.isNotEmpty()) {
                item {
                    BandSelector(bands, selectedIndex, maySelect) { if (editor?.state?.value?.isEditing != true) selectedIndex = it }
                }
                if (compact) item {
                    OutlinedButton(onClick = { sheet = PeqSheet.Bands }, enabled = maySelect, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text("全部频段")
                    }
                }
            }
            // Stable selected controls stay below the graph: nothing floats under a moving finger.
            if (selected != null && editor != null) item {
                ParamEqBandCard(selected, editor, state, editable,
                    onNumber = { parameter, requester ->
                        if (maySelect && !editor.state.value.isEditing) editor.state.value.draft.firstOrNull { it.index == selected.index }?.let {
                            sheet = PeqSheet.Number(editor, it, parameter, requester)
                        }
                    },
                    onNudge = { parameter, direction -> editor.state.value.draft.firstOrNull { it.index == selected.index }?.let { grouped(peqNudge(it, parameter, direction)) } })
            }
            if (editor != null) item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state?.error?.let { error ->
                        Text(errorMessage(error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (state?.phase == ParamEqEditPhase.FAILED && state.confirmed == null) {
                        Text("设备当前配置未知，写入可能已部分生效。", color = MaterialTheme.colorScheme.error)
                    }
                    OutlinedButton(onClick = { editor.refresh() }, enabled = enabled && idle,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(if (manual && state.phase == ParamEqEditPhase.PENDING) "重新读取（丢弃草稿）" else "重新读取")
                    }
                    OutlinedButton(onClick = { editor.undo() }, enabled = editable && state?.canUndo == true && !state.isEditing,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("撤销") }
                    OutlinedButton(onClick = { editor.state.value.draft.firstOrNull { it.index == selectedIndex }?.let { grouped(peqReset(it)) } }, enabled = maySelect && selected != null,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("重置当前段") }
                    TextButton(onClick = { flattenDialog = true }, enabled = maySelect && bands.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("平直…") }
                }
            }
            if (!compact && bands.isNotEmpty()) {
                item { Text("全部频段", style = MaterialTheme.typography.titleMedium) }
                items(bands, key = { it.index }) { band ->
                    // Wide layouts expose every raw-preserving parameter entry, using the same sheets and groups.
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { selectedIndex = band.index }, enabled = maySelect,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { stateDescription = if (band.index == selectedIndex) "已选中" else "未选中" }) {
                                Text("第 ${band.index + 1} 段")
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                PeqParameter.entries.forEach { parameter ->
                                    val requester = remember(editor, band.index, parameter) { FocusRequester() }
                                    OutlinedButton(onClick = { sheet = PeqSheet.Number(editor!!, band, parameter, requester) }, enabled = maySelect && (parameter != PeqParameter.GAIN || band.filter !in listOf(PeqFilter.LOW_PASS, PeqFilter.HIGH_PASS)),
                                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).focusRequester(requester).semantics { contentDescription = "第 ${band.index + 1} 段 ${parameterName(parameter)}"; stateDescription = parameterValue(band, parameter) }) {
                                        Text("${parameterName(parameter)}\n${parameterValue(band, parameter)}")
                                    }
                                }
                            }
                            OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(filterName(band.filter)) }
                        }
                    }
                }
            }
            item {
                if (bands.isNotEmpty()) Text("左轴 EQ 曲线为 48 kHz 参数响应估算，不含自动前置衰减，非耳机实测。", style = MaterialTheme.typography.bodySmall)
                if (bands.isNotEmpty()) Text("Bluetrum 验证模式：固定峰值，写入类型码 0。", style = MaterialTheme.typography.bodySmall)
                if (!compact && bands.isNotEmpty()) Text("鼠标：拖节点；Shift 锁轴；Q 手柄 / 滚轮；双击重置。方向键细调，Shift 大步、Ctrl 精细。", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (flattenDialog) {
        AlertDialog(onDismissRequest = { flattenDialog = false }, title = { Text("平直所有频段？") },
            text = { Text(if (manual)
                "将所有频段草稿设为平直，不发送到耳机；需要点击“提交一次”。这不是恢复出厂或保存到 Flash。"
                else "将所有频段设为平直并发送到耳机。这不是恢复出厂或保存到 Flash。") },
            confirmButton = { TextButton(enabled = maySelect, onClick = {
                flattenDialog = false
                if (maySelect) {
                    val latest = editor.state.value.draft
                    val changed = peqFlatten(latest)
                    if (changed != latest) { editor.beginEdit(); editor.replaceDraft(changed); editor.endEdit() }
                }
            }, modifier = Modifier.heightIn(min = 48.dp)) { Text("确认") } },
            dismissButton = { TextButton(onClick = { flattenDialog = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") } })
    }
    val currentSheet = sheet
    if (currentSheet != null && editable) {
        ModalBottomSheet(
            onDismissRequest = { closeSheet() },
            sheetState = rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
            ),
        ) {
            when (currentSheet) {
                PeqSheet.Bands -> {
                    Text("全部频段", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 600.dp).navigationBarsPadding()) {
                        items(bands, key = { it.index }) { band ->
                            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(selected = band.index == selectedIndex, enabled = maySelect, role = Role.RadioButton,
                                onClick = { selectedIndex = band.index; closeSheet() }).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = band.index == selectedIndex, onClick = null, enabled = maySelect)
                                Column(Modifier.padding(start = 12.dp)) { Text("第 ${band.index + 1} 段"); Text(bandSummary(band), style = MaterialTheme.typography.bodyMedium) }
                            }
                        }
                    }
                }
                is PeqSheet.Number -> key(currentSheet) {
                    PeqNumberSheet(currentSheet.band, currentSheet.parameter, onCancel = { closeSheet() }, onConfirm = { raw ->
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

private fun AncMode.display(): String = when (this) {
    AncMode.OFF -> "关闭"
    AncMode.NOISE_CANCELLING -> "降噪"
    AncMode.TRANSPARENCY -> "通透"
    AncMode.WIND -> "抗风噪"
    AncMode.ADAPTIVE -> "自适应"
    AncMode.LIVE -> "Live"
}

private fun errorMessage(error: Exception): String = when (error) {
    is DropException.Unverified -> "命令已发送，可能已部分应用，但读回未能验证；当前状态未知，请重新读取。"
    is DropException.AncModeMismatch -> "读回不一致；设备实际为 ${error.observed.display()}。"
    is DropException.CodecStateMismatch -> "${error.codec} 读回不一致；设备实际${if (error.observed) "开启" else "关闭"}。"
    else -> error.message ?: "未知错误"
}

private sealed interface PeqSheet {
    data object Bands : PeqSheet
    data class Number(val editor: ParamEqEditor, val band: GaiaPeqBand, val parameter: PeqParameter, val returnFocus: FocusRequester) : PeqSheet
}

private fun filterName(filter: PeqFilter): String = when (filter) {
    PeqFilter.PEAKING -> "钟形 / 峰值"
    PeqFilter.LOW_SHELF -> "低架"
    PeqFilter.HIGH_SHELF -> "高架"
    PeqFilter.LOW_PASS -> "低通"
    PeqFilter.HIGH_PASS -> "高通"
    PeqFilter.BYPASS -> "旁路"
}
private fun parameterName(parameter: PeqParameter): String = when (parameter) {
    PeqParameter.GAIN -> "增益"
    PeqParameter.FREQUENCY -> "频率"
    PeqParameter.Q -> "Q"
}
private fun parameterValue(band: GaiaPeqBand, parameter: PeqParameter): String = when (parameter) {
    PeqParameter.GAIN -> "${(band.gainDb * 100).roundToInt() / 100.0} dB"
    PeqParameter.FREQUENCY -> "${band.frequencyHz} Hz"
    PeqParameter.Q -> "Q ${(band.q * 1000).roundToInt() / 1000.0}"
}
private fun bandSummary(band: GaiaPeqBand): String = "${band.frequencyHz} Hz · ${parameterValue(band, PeqParameter.GAIN)} · ${parameterValue(band, PeqParameter.Q)} · ${filterName(band.filter)}"
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

@Composable
private fun BandSelector(bands: List<GaiaPeqBand>, selectedIndex: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
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
                }.semantics { contentDescription = "第 ${band.index + 1} 段，${band.frequencyHz} Hz" },
                label = { Text("${band.index + 1} · ${band.frequencyHz} Hz") })
        }
    }
}

@Composable
private fun ParamEqBandCard(
    band: GaiaPeqBand, editor: ParamEqEditor, state: ParamEqEditState?, enabled: Boolean,
    onNumber: (PeqParameter, FocusRequester) -> Unit, onNudge: (PeqParameter, Int) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("第 ${band.index + 1} 段", style = MaterialTheme.typography.titleMedium)
            if (band.filter == PeqFilter.BYPASS) Text("旁路：保留原始数值。选择其他滤波器后启用参数。")
            PeqParameter.entries.forEach { parameter ->
                PeqParameterControl(band, parameter, editor, state, enabled, onNumber, onNudge)
            }
            OutlinedButton(onClick = {}, enabled = false,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = "第 ${band.index + 1} 段滤波器"; stateDescription = filterName(band.filter) }) { Text(filterName(band.filter)) }
        }
    }
}

@Composable
private fun PeqParameterControl(
    band: GaiaPeqBand, parameter: PeqParameter, editor: ParamEqEditor, state: ParamEqEditState?, enabled: Boolean,
    onNumber: (PeqParameter, FocusRequester) -> Unit, onNudge: (PeqParameter, Int) -> Unit,
) {
    val requester = remember(editor, band.index, parameter) { FocusRequester() }
    var sliderGroup by remember(editor, band.index, parameter) { mutableStateOf(false) }
    val latestBand by rememberUpdatedState(band)
    val name = parameterName(parameter)
    val description = "第 ${band.index + 1} 段 $name"
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
        if (!inRange && applicable) Text("超出滑杆范围，点数值编辑", style = MaterialTheme.typography.bodySmall)
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
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = "$description 减小" }) { Text("−") }
            OutlinedButton(onClick = { onNudge(parameter, 1) }, enabled = sliderEnabled && state?.isEditing != true,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = "$description 增大" }) { Text("+") }
        }
    }
}

@Composable
private fun PeqNumberSheet(band: GaiaPeqBand, parameter: PeqParameter, onCancel: () -> Unit, onConfirm: (Int?) -> Unit) {
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
        Text("第 ${band.index + 1} 段 · ${parameterName(parameter)}", style = MaterialTheme.typography.titleLarge)
        Text(when (parameter) {
            PeqParameter.FREQUENCY -> "20–20000 Hz，整数"
            PeqParameter.GAIN -> "−12–+12 dB"
            PeqParameter.Q -> "Q ${1.0 / 4096}–${65535.0 / 4096}"
        })
        OutlinedTextField(value = text, onValueChange = { text = it; invalid = false }, singleLine = true, isError = invalid,
            label = { Text(parameterName(parameter)) },
            keyboardOptions = KeyboardOptions(keyboardType = if (parameter == PeqParameter.FREQUENCY) KeyboardType.Number else KeyboardType.Decimal, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { confirm() }),
            modifier = Modifier.fillMaxWidth().focusRequester(requester).semantics { contentDescription = "第 ${band.index + 1} 段 ${parameterName(parameter)}" })
        if (parameter == PeqParameter.GAIN) OutlinedButton(onClick = {
            text = if (text.trim().startsWith('-')) text.trim().removePrefix("-") else "-${text.trim().removePrefix("+")}"
            invalid = false
        }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("切换正 / 负号") }
        if (invalid) Text("请输入范围内的有效数值。只允许一个小数点或逗号，不支持千位分组。", color = MaterialTheme.colorScheme.error)
        Button(onClick = { confirm() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("确认") }
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("取消") }
    }
}
