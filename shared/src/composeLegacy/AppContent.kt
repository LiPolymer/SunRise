package ink.lipoly.app.sunrise.composeLegacy

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.material3.Switch
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import ink.lipoly.app.sunrise.headset.HeadsetClient
import ink.lipoly.app.sunrise.headset.HeadsetDevice
import ink.lipoly.app.sunrise.headset.HeadsetEvent
import ink.lipoly.app.sunrise.drop.DropException
import ink.lipoly.app.sunrise.headset.HeadsetPhase
import ink.lipoly.app.sunrise.headset.HeadsetState
import ink.lipoly.app.sunrise.drop.GaiaIds
import ink.lipoly.app.sunrise.drop.AudioCodec
import ink.lipoly.app.sunrise.catalog.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

internal enum class MainPage { OVERVIEW, EQUALIZER, SETTINGS }

internal class AppNavigationState {
    var page by mutableStateOf(MainPage.OVERVIEW)
    var diagnostics by mutableStateOf(false)
    var catalogue by mutableStateOf(false)
}

@Composable
internal fun rememberAppNavigationState(): AppNavigationState = remember { AppNavigationState() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppContent(
    client: HeadsetClient?,
    documents: CatalogDocuments,
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
        val catalogState by Catalog.state.collectAsState()
        val inspectionMode = LocalInspectionMode.current
        var pullJob by remember { mutableStateOf<Job?>(null) }
        var documentBusy by remember { mutableStateOf(false) }
        var importPreview by remember { mutableStateOf<CatalogSnapshot?>(null) }
        var documentError by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()
        val snackbar = remember { SnackbarHostState() }
        var candidates by remember(client) { mutableStateOf<List<HeadsetDevice>>(emptyList()) }
        var chooserOpen by remember(client) { mutableStateOf(false) }
        var selectionPrompted by remember(client) { mutableStateOf(false) }
        var referenceChooserOpen by remember { mutableStateOf(false) }
        var previewReferenceUuid by remember { mutableStateOf<String?>(null) }
        val snapshot = catalogState.snapshot
        val audioAddress = state.device?.address?.uppercase()
        val manualReferenceUuid = if (audioAddress == null) previewReferenceUuid else settings.referenceProductByAddress[audioAddress]
        val reference = remember(snapshot, state.device?.name, manualReferenceUuid, english) {
            Catalog.resolveReference(snapshot, state.device?.name, manualReferenceUuid, english)
        }
        val visibleCandidates = remember(candidates, snapshot, settings.catalogOnlyDevices) {
            if (settings.catalogOnlyDevices) candidates.filter { Catalog.matchesDevice(snapshot, it.name) } else candidates
        }
        val autoFilter = remember(snapshot, settings.catalogOnlyDevices, catalogState.loading) {
            val loaded = !catalogState.loading
            val onlyCatalog = settings.catalogOnlyDevices
            val accept: (HeadsetDevice) -> Boolean = { device -> loaded && (!onlyCatalog || Catalog.matchesDevice(snapshot, device.name)) }
            accept
        }
        val latestAutoFilter by rememberUpdatedState(autoFilter)
        var recentEvents by remember(client) { mutableStateOf<List<HeadsetEvent>>(emptyList()) }
        var confirmedReads by remember(client, state.device?.device, state.phase, state.controls.phase) {
            mutableStateOf<Set<OverviewControl>>(emptySet())
        }
        var working by remember(client) { mutableStateOf<String?>(null) }
        var notice by remember { mutableStateOf<String?>(null) }
        var refreshGeneration by remember { mutableIntStateOf(0) }
        val controls = if (client != null && missingPermissions.isEmpty() &&
            state.phase == HeadsetPhase.READY && state.controls.hasReadyGaia()) {
            try { client.gaia } catch (_: DropException) { null }
        } else null
        val eqEditor = remember(controls) { controls?.let { ParamEqEditor(scope, it) } }
        DisposableEffect(eqEditor) { onDispose { eqEditor?.close() } }
        val eqState = eqEditor?.state?.collectAsState()?.value
        val eqBusy = eqState?.isEditing == true ||
            eqState?.phase == ParamEqEditPhase.PENDING || eqState?.phase == ParamEqEditPhase.WRITING
        LaunchedEffect(eqState?.error) {
            eqState?.error?.let { notice = errorMessage(it, english) }
        }
        LaunchedEffect(controls, refreshGeneration) {
            val bound = controls ?: return@LaunchedEffect
            if (GaiaIds.CODEC_TYPE !in state.controls.capabilities.gaiaFeatures &&
                state.controls.capabilities.complete) return@LaunchedEffect
            for (codec in AudioCodec.entries) {
                while (working != null) delay(100.milliseconds)
                try {
                    bound.isCodecEnabled(codec)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Each codec is independently supported; absent values remain unknown.
                }
            }
        }

        LaunchedEffect(notice) {
            notice?.let {
                snackbar.showSnackbar(it)
                notice = null
            }
        }
        LaunchedEffect(catalogState.error) {
            catalogState.error?.let { notice = it }
        }
        LaunchedEffect(client) {
            client?.events?.collect { event ->
                recentEvents = (recentEvents + event).takeLast(10)
            }
        }
        LaunchedEffect(client, missingPermissions.isEmpty(), catalogState.loading) {
            client?.setAutoDeviceFilter(latestAutoFilter)
            if (client != null && missingPermissions.isEmpty() && !catalogState.loading) client.startAutoConnect()
        }
        LaunchedEffect(client, autoFilter) {
            client?.setAutoDeviceFilter(autoFilter)
        }
        LaunchedEffect(client, state.phase, missingPermissions.isEmpty()) {
            if (client != null && missingPermissions.isEmpty() && state.phase == HeadsetPhase.SELECTION_REQUIRED) {
                try {
                    candidates = client.discoverConnectedDevices()
                    if (!selectionPrompted) {
                        chooserOpen = true
                        selectionPrompted = true
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    notice = tr(english, "发现设备失败：", "Device discovery failed: ") + errorMessage(e, english)
                }
            } else if (state.phase == HeadsetPhase.IDLE || state.phase == HeadsetPhase.READY) {
                selectionPrompted = false
            }
        }
        LaunchedEffect(client, state.phase, state.controls.phase, state.device?.device, missingPermissions.isEmpty(), refreshGeneration) {
            confirmedReads = emptySet()
            if (client == null || missingPermissions.isNotEmpty() ||
                state.phase != HeadsetPhase.READY || !state.controls.hasReadyGaia()) return@LaunchedEffect
            delay(500.milliseconds) // Let the client's initial battery/ANC reads run first.
            val advertised = state.controls.capabilities.gaiaFeatures
            val incomplete = !state.controls.capabilities.complete
            for (control in OverviewControl.entries) {
                val feature = when (control) {
                    OverviewControl.GAIN -> GaiaIds.DAC_GAIN
                    OverviewControl.LED -> GaiaIds.LED
                    OverviewControl.SPATIAL, OverviewControl.HEAD_TRACKING -> GaiaIds.SPATIAL_AUDIO
                }
                if (feature !in advertised && !incomplete) continue
                if (control == OverviewControl.HEAD_TRACKING && feature !in advertised &&
                    OverviewControl.SPATIAL !in confirmedReads) continue
                while (working != null) delay(100.milliseconds)
                try {
                    val current = client.state.value
                    if (current.phase != HeadsetPhase.READY || !current.controls.hasReadyGaia()) return@LaunchedEffect
                    when (control) {
                        OverviewControl.GAIN -> client.gaia.getGain()
                        OverviewControl.LED -> client.gaia.isLedOn()
                        OverviewControl.SPATIAL -> client.gaia.isSpatialOn()
                        OverviewControl.HEAD_TRACKING -> client.gaia.getHeadTracking()
                    }
                    confirmedReads = confirmedReads + control
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Feature lists may be incomplete; failed optional reads do not block the page.
                }
            }
        }

        fun runAction(label: String, control: Boolean = false, action: suspend HeadsetClient.() -> Unit) {
            val activeClient = client ?: return
            if (working != null || missingPermissions.isNotEmpty()) return
            if (control && (activeClient.state.value.phase != HeadsetPhase.READY ||
                !activeClient.state.value.controls.hasReadyGaia())) return
            working = label
            scope.launch {
                try {
                    if (control && (activeClient.state.value.phase != HeadsetPhase.READY ||
                        !activeClient.state.value.controls.hasReadyGaia())) throw DropException.NotReady()
                    activeClient.action()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    notice = tr(english, "$label：", "$label: ") + errorMessage(e, english)
                } finally {
                    working = null
                }
            }
        }

        fun chooseHeadset() {
            if (client == null || missingPermissions.isNotEmpty() || working != null) return
            scope.launch {
                try {
                    candidates = client.discoverConnectedDevices()
                    chooserOpen = true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    notice = tr(english, "发现设备失败：", "Device discovery failed: ") + errorMessage(error, english)
                }
            }
        }

        fun retryConnection() {
            if (client == null || missingPermissions.isNotEmpty() || catalogState.loading) return
            if (state.phase == HeadsetPhase.SELECTION_REQUIRED) chooseHeadset()
            else {
                client.setAutoDeviceFilter(latestAutoFilter)
                client.startAutoConnect()
            }
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

        fun importCatalog() {
            if (inspectionMode) {
                documentError = "Catalog writes are unavailable in preview"
                notice = documentError
                return
            }
            if (catalogState.busy || catalogState.loading || documentBusy || importPreview != null) return
            documentBusy = true
            scope.launch {
                try {
                    documents.openImport()?.let { importPreview = Catalog.prepareImport(it) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    documentError = errorMessage(error, english)
                    notice = documentError
                } finally {
                    documentBusy = false
                }
            }
        }

        fun exportCatalog() {
            if (inspectionMode) {
                documentError = "Catalog writes are unavailable in preview"
                notice = documentError
                return
            }
            if (catalogState.busy || documentBusy || importPreview != null || catalogState.snapshot == null) return
            val bytes = Catalog.exportBytes()
            documentBusy = true
            scope.launch {
                try {
                    if (documents.saveExport(bytes, Catalog.EXPORT_NAME)) {
                        documentError = null
                        notice = tr(english, "已导出完整数据库", "Complete database exported")
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    documentError = errorMessage(error, english)
                    notice = documentError
                } finally {
                    documentBusy = false
                }
            }
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
                                refreshGeneration++
                                if (state.phase == HeadsetPhase.READY && state.controls.hasReadyGaia()) {
                                    runAction(tr(english, "刷新", "Refresh"), control = true) {
                                        var firstFailure: Exception? = null
                                        if (state.controls.capabilities.ancModes.isNotEmpty()) {
                                            try {
                                                gaia.getAncMode()
                                            } catch (e: CancellationException) {
                                                throw e
                                            } catch (e: Exception) {
                                                firstFailure = e
                                            }
                                        }
                                        try {
                                            gaia.getBattery()
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            if (firstFailure == null) firstFailure = e
                                        }
                                        firstFailure?.let { throw it }
                                    }
                                } else if (state.phase != HeadsetPhase.READY) {
                                    retryConnection()
                                }
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
                    catalogMatched = Catalog.matchesDevice(snapshot, state.device?.name),
                    referenceName = reference.product?.name,
                    onCatalogFilterChange = { onSettingsChange(settings.copy(catalogOnlyDevices = it)) },
                    onChooseHeadset = ::chooseHeadset,
                    onOpenCatalog = { navigation.catalogue = true },
                    confirmedReads = confirmedReads,
                    working = working,
                    codecBlocked = eqBusy,
                    modifier = Modifier.fillMaxSize().padding(padding),
                    onRequestPermissions = onRequestPermissions,
                    onRetry = ::retryConnection,
                    onAnc = { mode -> runAction(tr(english, "设置降噪", "Set ANC"), control = true) { gaia.setAncMode(mode) } },
                    onGain = { gain -> runAction(tr(english, "设置增益", "Set gain"), control = true) { gaia.setGain(gain) } },
                    onLed = { enabled -> runAction("LED", control = true) { gaia.setLedOn(enabled) } },
                    onSpatial = { enabled -> runAction(tr(english, "空间音频", "Spatial audio"), control = true) { gaia.setSpatialOn(enabled) } },
                    onTracking = { mode -> runAction(tr(english, "头部追踪", "Head tracking"), control = true) { gaia.setHeadTracking(mode) } },
                    onCodec = { codec, enabled ->
                        if (!eqBusy) {
                            val bound = controls
                            runAction(tr(english, "设置编码", "Set codec"), control = true) {
                                bound?.setCodecEnabled(codec, enabled) ?: throw DropException.NotReady()
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
                    referenceSource = snapshot?.let { current -> reference.product?.let { Catalog.sourceUrl(current, it) } },
                    referenceRetrievedAt = snapshot?.retrievedAt,
                    referenceResponseHash = reference.product?.freqResponse?.let { snapshot?.responseHashesByPath?.get(it) },
                    showReferenceResponse = settings.showReferenceResponse,
                    includeResponsePreGain = settings.includeResponsePreGain,
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
                        busy = catalogState.busy || documentBusy || importPreview != null,
                        error = documentError ?: catalogState.error,
                    ),
                    canCancelPull = pullJob?.isActive == true,
                    onPull = { cdn ->
                        if (inspectionMode) {
                            documentError = "Catalog pulls are unavailable in preview"
                            notice = documentError
                        } else if (pullJob?.isActive != true && !Catalog.state.value.busy && !catalogState.loading && !documentBusy && importPreview == null) {
                            documentError = null
                            pullJob = scope.launch {
                                try {
                                    Catalog.pull(cdn)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    documentError = errorMessage(error, english)
                                    notice = documentError
                                } finally { pullJob = null }
                            }
                        }
                    },
                    onCancelPull = { pullJob?.cancel() },
                    onImport = ::importCatalog,
                    onExport = ::exportCatalog,
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

        importPreview?.let { preview ->
            AlertDialog(
                onDismissRequest = { importPreview = null },
                title = { Text(tr(english, "替换当前数据库？", "Replace the current database?")) },
                text = {
                    Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                        CatalogSnapshotDetails(preview, english)
                        Text(tr(english, "来源信息由导入文件提供。哈希仅验证文件一致性，不代表官方认证；替换而非合并，允许较旧快照。",
                            "Source information is supplied by the imported file. Hashes verify consistency, not official authenticity. This replaces, not merges; older snapshots are allowed."))
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (inspectionMode) {
                            documentError = "Catalog writes are unavailable in preview"
                            notice = documentError
                        } else {
                            importPreview = null
                            documentBusy = true
                            scope.launch {
                                try {
                                    Catalog.importSnapshot(preview)
                                    documentError = null
                                    if (Catalog.state.value.error == null) notice = tr(english, "数据库已替换", "Database replaced")
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    documentError = errorMessage(error, english)
                                    notice = documentError
                                } finally { documentBusy = false }
                            }
                        }
                    }) { Text(tr(english, "替换当前数据库", "Replace current database")) }
                },
                dismissButton = { TextButton(onClick = { importPreview = null }) { Text(tr(english, "取消", "Cancel")) } },
            )
        }

        if (chooserOpen) {
            AlertDialog(
                onDismissRequest = { chooserOpen = false },
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
                            val match = Catalog.matchesDevice(snapshot, candidate.name)
                            val response = Catalog.resolveReference(snapshot, candidate.name, null, english).response
                            TextButton(
                                onClick = {
                                    chooserOpen = false
                                    runAction(tr(english, "连接", "Connect")) { connect(candidate) }
                                },
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
                    TextButton(onClick = { chooserOpen = false }) { Text(tr(english, "稍后", "Later")) }
                },
            )
        }
}

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
