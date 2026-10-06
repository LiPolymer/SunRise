package ink.lipoly.app.sunrise.composeLegacy

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.Switch
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import ink.lipoly.app.sunrise.headset.HeadsetClient
import ink.lipoly.app.sunrise.drop.DropException
import ink.lipoly.app.sunrise.headset.HeadsetPhase
import ink.lipoly.app.sunrise.headset.HeadsetState
import ink.lipoly.app.sunrise.catalog.*
import ink.lipoly.app.sunrise.settings.UiSettings
import ink.lipoly.app.sunrise.presentation.PresentationNotice
import ink.lipoly.app.sunrise.presentation.PresentationSession
import ink.lipoly.app.sunrise.controls.peq.ParamEqEditPhase
import ink.lipoly.app.sunrise.controls.peq.ParamEqScreen

internal enum class MainPage { OVERVIEW, EQUALIZER, SETTINGS }

internal class AppNavigationState {
    var page by mutableStateOf(MainPage.OVERVIEW)
    var diagnostics by mutableStateOf(false)
    var catalogue by mutableStateOf(false)
}

@Composable
internal fun rememberAppNavigationState(): AppNavigationState = remember { AppNavigationState() }

@Composable
internal fun AppContent(
    catalog: Catalog,
    client: HeadsetClient?,
    session: PresentationSession,
    missingPermissions: Set<String>,
    onRequestPermissions: () -> Unit,
    settings: UiSettings,
    english: Boolean,
    dynamicColorAvailable: Boolean,
    navigation: AppNavigationState,
    onSettingsChange: (UiSettings) -> Unit,
) {
        val page = navigation.page
        val diagnostics = navigation.diagnostics
        val state = client?.state?.collectAsState()?.value ?: HeadsetState()
        val catalogState by catalog.state.collectAsState()
        val headsetState by session.headset.state.collectAsState()
        val operationState by session.catalog.state.collectAsState()
        val snackbar = remember { SnackbarHostState() }
        val candidates = headsetState.candidates
        val chooserOpen = headsetState.chooserOpen
        var referenceChooserOpen by remember { mutableStateOf(false) }
        var targetChooserOpen by remember { mutableStateOf(false) }
        var previewReferenceUuid by remember { mutableStateOf<String?>(null) }
        val snapshot = catalogState.snapshot
        val audioAddress = state.device?.address?.uppercase()
        val manualReferenceUuid = if (audioAddress == null) previewReferenceUuid else settings.referenceProductByAddress[audioAddress]
        val reference = remember(snapshot, state.device?.name, manualReferenceUuid, english) {
            resolveCatalogReference(snapshot, state.device?.name, manualReferenceUuid, english)
        }
        val targetProduct = settings.targetProductUuid?.let { snapshot?.productsByUuid?.get(it) }
        val targetResponse = targetProduct?.freqResponse?.let { snapshot?.responsesByPath?.get(it) }
        val visibleCandidates = remember(candidates, snapshot, settings.catalogOnlyDevices) {
            if (settings.catalogOnlyDevices) candidates.filter { matchesCatalogDevice(snapshot, it.name) } else candidates
        }
        val recentEvents = headsetState.recentEvents
        val confirmedReads = headsetState.confirmedReads
        val working = headsetState.working
        var notice by remember { mutableStateOf<String?>(null) }
        val controls = if (client != null && missingPermissions.isEmpty() &&
            state.phase == HeadsetPhase.READY && state.controls.hasReadyGaia()) {
            try { client.gaia } catch (_: DropException) { null }
        } else null
        val eqEditor = remember(session.eq, controls) { session.eq.bind(controls) }
        val eqState = eqEditor?.state?.collectAsState()?.value
        val eqBusy = eqState?.isEditing == true ||
            eqState?.phase == ParamEqEditPhase.PENDING || eqState?.phase == ParamEqEditPhase.WRITING
        LaunchedEffect(eqState?.error) {
            eqState?.error?.let { notice = errorMessage(it, english) }
        }

        LaunchedEffect(notice) {
            notice?.let {
                snackbar.showSnackbar(it)
                notice = null
            }
        }
        LaunchedEffect(headsetState.notice, english) {
            headsetState.notice?.let { expected ->
                snackbar.showSnackbar(expected.message(english))
                session.headset.clearNotice(expected)
            }
        }
        LaunchedEffect(operationState.notice, english) {
            operationState.notice?.let { expected ->
                snackbar.showSnackbar(expected.message(english))
                session.catalog.clearNotice(expected)
            }
        }
        LaunchedEffect(catalogState.error) {
            catalogState.error?.let { notice = it }
        }

        fun chooseReference(product: CatalogProduct) {
            val address = client?.state?.value?.device?.address?.uppercase()
            if (address == null) previewReferenceUuid = product.uuid
            else onSettingsChange(settings.copy(referenceProductByAddress = settings.referenceProductByAddress + (address to product.uuid)))
            referenceChooserOpen = false
        }

        fun resetReference() {
            if (audioAddress == null) previewReferenceUuid = null
            else onSettingsChange(settings.copy(referenceProductByAddress = settings.referenceProductByAddress - audioAddress))
        }

        fun chooseTarget(product: CatalogProduct) {
            onSettingsChange(settings.copy(targetProductUuid = product.uuid, showTargetResponse = true))
            targetChooserOpen = false
        }

        fun clearTarget() {
            onSettingsChange(settings.copy(targetProductUuid = null))
        }

        val title = when {
            diagnostics -> tr(english, "高级诊断", "Advanced diagnostics")
            navigation.catalogue -> tr(english, "产品与频响目录", "Product and response catalog")
            page == MainPage.OVERVIEW -> "SunRise"
            page == MainPage.EQUALIZER -> tr(english, "参数均衡器", "Parametric EQ")
            else -> tr(english, "设置", "Settings")
        }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        if (diagnostics || navigation.catalogue) TextButton(onClick = {
                            navigation.diagnostics = false
                            navigation.catalogue = false
                        }) {
                            Text(tr(english, "返回", "Back"))
                        }
                    },
                    actions = {
                        if (!diagnostics && !navigation.catalogue && page == MainPage.OVERVIEW) {
                            TextButton(onClick = {
                                session.headset.refresh(tr(english, "刷新", "Refresh"))
                            }, enabled = client != null && missingPermissions.isEmpty() && working == null &&
                                (state.phase != HeadsetPhase.READY || state.controls.hasReadyGaia())) {
                                Text(tr(english, "刷新", "Refresh"))
                            }
                        }
                    },
                )
            },
            bottomBar = {
                if (!diagnostics) NavigationBar {
                    NavigationBarItem(
                        selected = page == MainPage.OVERVIEW,
                        onClick = { navigation.catalogue = false; navigation.page = MainPage.OVERVIEW },
                        icon = { Text("◉", modifier = Modifier.clearAndSetSemantics {}) },
                        label = { Text(tr(english, "概览", "Overview")) },
                    )
                    NavigationBarItem(
                        selected = page == MainPage.EQUALIZER,
                        onClick = { navigation.catalogue = false; navigation.page = MainPage.EQUALIZER },
                        icon = { Text("≋", modifier = Modifier.clearAndSetSemantics {}) },
                        label = { Text(tr(english, "均衡器", "Equalizer")) },
                    )
                    NavigationBarItem(
                        selected = page == MainPage.SETTINGS,
                        onClick = { navigation.catalogue = false; navigation.page = MainPage.SETTINGS },
                        icon = { Text("⚙", modifier = Modifier.clearAndSetSemantics {}) },
                        label = { Text(tr(english, "设置", "Settings")) },
                    )
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            when {
                navigation.catalogue -> ProductCatalogScreen(
                    snapshot = snapshot,
                    english = english,
                    deviceName = state.device?.name,
                    deviceEqBands = eqState?.confirmed?.bands?.size,
                    onUseReference = {
                        chooseReference(it)
                        navigation.catalogue = false
                        navigation.page = MainPage.EQUALIZER
                    },
                    modifier = Modifier.fillMaxSize().padding(padding),
                )
                diagnostics -> DropDiagnosticsScreen(
                    client = client,
                    missingPermissions = missingPermissions,
                    onRequestPermissions = onRequestPermissions,
                    modifier = Modifier.fillMaxSize().padding(padding),
                    english = english,
                    recentEvents = recentEvents,
                )
                page == MainPage.OVERVIEW -> OverviewScreen(
                    state = state,
                    clientAvailable = client != null,
                    missingPermissions = missingPermissions,
                    english = english,
                    showWind = settings.showWind,
                    catalogOnlyDevices = settings.catalogOnlyDevices,
                    catalogLoading = catalogState.loading,
                    catalogAvailable = snapshot != null,
                    catalogError = catalogState.error,
                    catalogMatched = matchesCatalogDevice(snapshot, state.device?.name),
                    referenceName = reference.product?.name,
                    onCatalogFilterChange = { onSettingsChange(settings.copy(catalogOnlyDevices = it)) },
                    onChooseHeadset = session.headset::chooseHeadset,
                    onOpenCatalog = { navigation.catalogue = true },
                    confirmedReads = confirmedReads,
                    working = working,
                    codecBlocked = eqBusy,
                    modifier = Modifier.fillMaxSize().padding(padding),
                    onRequestPermissions = onRequestPermissions,
                    onRetry = session.headset::retryConnection,
                    onAnc = { mode -> session.headset.runAction(tr(english, "设置降噪", "Set ANC"), control = true) { gaia.setAncMode(mode) } },
                    onGain = { gain -> session.headset.runAction(tr(english, "设置增益", "Set gain"), control = true) { gaia.setGain(gain) } },
                    onLed = { enabled -> session.headset.runAction("LED", control = true) { gaia.setLedOn(enabled) } },
                    onSpatial = { enabled -> session.headset.runAction(tr(english, "空间音频", "Spatial audio"), control = true) { gaia.setSpatialOn(enabled) } },
                    onTracking = { mode -> session.headset.runAction(tr(english, "头部追踪", "Head tracking"), control = true) { gaia.setHeadTracking(mode) } },
                    onCodec = { codec, enabled ->
                        if (!eqBusy) {
                            session.headset.runAction(tr(english, "设置编码", "Set codec"), control = true) {
                                controls?.setCodecEnabled(codec, enabled) ?: throw DropException.NotReady()
                                eqEditor?.refresh()
                            }
                        }
                    },
                )
                page == MainPage.EQUALIZER -> ParamEqScreen(
                    editor = eqEditor,
                    state = eqState,
                    english = english,
                    enabled = working == null && missingPermissions.isEmpty(),
                    referenceProduct = reference.product,
                    referenceResponse = reference.response,
                    referenceSource = snapshot?.let { current -> reference.product?.let { catalogSourceUrl(current, it) } },
                    referenceRetrievedAt = snapshot?.retrievedAt,
                    referenceResponseHash = reference.product?.freqResponse?.let { snapshot?.responseHashesByPath?.get(it) },
                    showReferenceResponse = settings.showReferenceResponse,
                    includeResponsePreGain = settings.includeResponsePreGain,
                    targetProduct = targetProduct,
                    targetResponse = targetResponse,
                    targetProductUuid = settings.targetProductUuid,
                    targetResponseHash = targetProduct?.freqResponse?.let { snapshot?.responseHashesByPath?.get(it) },
                    showTargetResponse = settings.showTargetResponse,
                    onChooseTarget = {
                        if (snapshot != null) targetChooserOpen = true
                        else notice = tr(english, "没有可用数据库，请先在设置中导入或拉取", "No usable database; import or pull in Settings first")
                    },
                    onClearTarget = ::clearTarget,
                    onTargetResponseChange = { onSettingsChange(settings.copy(showTargetResponse = it)) },
                    onChooseReference = {
                        if (snapshot != null) referenceChooserOpen = true
                        else notice = tr(english, "没有可用数据库，请先在设置中导入或拉取", "No usable database; import or pull in Settings first")
                    },
                    onResetReference = ::resetReference,
                    onReferenceResponseChange = { onSettingsChange(settings.copy(showReferenceResponse = it)) },
                    onResponsePreGainChange = { onSettingsChange(settings.copy(includeResponsePreGain = it)) },
                    modifier = Modifier.fillMaxSize().padding(padding),
                    filterSelectionEnabled = false,
                )
                else -> SettingsScreen(
                    settings = settings,
                    english = english,
                    dynamicAvailable = dynamicColorAvailable,
                    catalogState = catalogState.copy(
                        busy = catalogState.busy || operationState.documentBusy || operationState.importPreview != null,
                        error = operationState.error?.let { errorMessage(it, english) } ?: catalogState.error,
                    ),
                    canCancelPull = operationState.pullActive,
                    onPull = session.catalog::pull,
                    onCancelPull = session.catalog::cancelPull,
                    onImport = session.catalog::prepareImport,
                    onExport = session.catalog::export,
                    onOpenCatalog = { navigation.catalogue = true },
                    missingPermissions = missingPermissions,
                    clientAvailable = client != null,
                    modifier = Modifier.fillMaxSize().padding(padding),
                    onChange = onSettingsChange,
                    onRequestPermissions = onRequestPermissions,
                    onOpenDiagnostics = { navigation.diagnostics = true },
                )
            }
        }

        if (reference.invalidManualBinding) {
            LaunchedEffect(snapshot, manualReferenceUuid) {
                notice = tr(english, "绑定的目录 UUID 已不在当前数据库中，已恢复自动匹配；绑定未迁移到其他记录。",
                    "The bound catalog UUID is absent from this database. Automatic matching is used; the binding was not migrated to another record.")
            }
        }
        if (referenceChooserOpen && snapshot != null) CatalogProductSelector(
            snapshot = snapshot,
            deviceName = state.device?.name,
            english = english,
            onSelect = ::chooseReference,
            onDismiss = { referenceChooserOpen = false },
        )
        if (targetChooserOpen && snapshot != null) CatalogProductSelector(
            snapshot = snapshot,
            deviceName = state.device?.name,
            english = english,
            onSelect = ::chooseTarget,
            onDismiss = { targetChooserOpen = false },
            title = tr(english, "选择目标频响", "Choose a target response"),
        )

        operationState.importPreview?.let { preview ->
            AlertDialog(
                onDismissRequest = session.catalog::dismissImport,
                title = { Text(tr(english, "替换当前数据库？", "Replace the current database?")) },
                text = {
                    Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                        CatalogSnapshotDetails(preview, english)
                        Text(tr(english, "来源信息由导入文件提供。哈希仅验证文件一致性，不代表官方认证；替换而非合并，允许较旧快照。",
                            "Source information is supplied by the imported file. Hashes verify consistency, not official authenticity. This replaces, not merges; older snapshots are allowed."))
                    }
                },
                confirmButton = {
                    TextButton(onClick = session.catalog::confirmImport) { Text(tr(english, "替换当前数据库", "Replace current database")) }
                },
                dismissButton = { TextButton(onClick = session.catalog::dismissImport) { Text(tr(english, "取消", "Cancel")) } },
            )
        }

        if (chooserOpen) {
            AlertDialog(
                onDismissRequest = session.headset::dismissChooser,
                title = { Text(tr(english, "选择耳机", "Choose a headset")) },
                text = {
                    Column(modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(tr(english, "显示全部", "Show all"))
                            Switch(checked = !settings.catalogOnlyDevices,
                                onCheckedChange = { onSettingsChange(settings.copy(catalogOnlyDevices = !it)) })
                        }
                        Text(tr(english, "已隐藏 ${candidates.size - visibleCandidates.size} 台；目录匹配不代表协议支持。",
                            "${candidates.size - visibleCandidates.size} hidden; a catalogue match does not imply protocol support."))
                        if (visibleCandidates.isEmpty()) Text(
                            if (candidates.isEmpty()) tr(english, "未找到已连接的音频设备", "No connected audio devices found")
                            else tr(english, "没有匹配目录的设备，可切换“显示全部”", "No catalogue matches. Switch on Show all."),
                        )
                        visibleCandidates.forEach { candidate ->
                            val match = matchesCatalogDevice(snapshot, candidate.name)
                            val response = resolveCatalogReference(snapshot, candidate.name, null, english).response
                            TextButton(
                                onClick = { session.headset.select(candidate, tr(english, "连接", "Connect")) },
                                enabled = working == null && missingPermissions.isEmpty(),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column {
                                    Text("${candidate.name ?: tr(english, "未知设备", "Unknown device")} · ${candidate.address}")
                                    Text((if (match) tr(english, "目录匹配", "Catalogue match") else tr(english, "未收录名称", "Unlisted name")) + " · " + when (response) {
                                        is CatalogResponse.Ready -> tr(english, "有参考频响", "Reference available")
                                        is CatalogResponse.Unavailable -> tr(english, "参考无法解析", "Reference unparseable")
                                        null -> tr(english, "无参考频响", "No reference response")
                                    })
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = session.headset::dismissChooser) { Text(tr(english, "稍后", "Later")) }
                },
            )
        }
}

private fun PresentationNotice.message(english: Boolean): String =
    tr(english, chinese, this.english) + (error?.let { errorMessage(it, english) } ?: "")

internal fun errorMessage(error: Exception, english: Boolean): String = when (error) {
    is DropException.Unverified -> tr(
        english,
        "命令已发送，可能已部分应用，但读回未能验证；当前状态未知，请重新读取。",
        "Command sent and may be partially applied, but readback failed. Current state is unknown; reload.",
    )
    is DropException.AncModeMismatch -> tr(
        english,
        "读回不一致；设备实际为 ${error.observed.display(false)}。",
        "Readback mismatch; device reports ${error.observed.display(true)}.",
    )
    is DropException.CodecStateMismatch -> tr(
        english,
        "${error.codec} 读回不一致；设备实际${if (error.observed) "开启" else "关闭"}。",
        "${error.codec} readback mismatch; the device reports ${if (error.observed) "enabled" else "disabled"}.",
    )
    else -> error.message ?: tr(english, "未知错误", "Unknown error")
}
