package ink.lipoly.app.sunrise.compose

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import ink.lipoly.app.sunrise.catalog.*
import ink.lipoly.app.sunrise.compose.OurNavStack.activeNode
import ink.lipoly.app.sunrise.compose.OurNavStack.back
import ink.lipoly.app.sunrise.compose.OurNavStack.createNavigationBarItems
import ink.lipoly.app.sunrise.compose.entries.*
import ink.lipoly.app.sunrise.compose.FancyEqualizer.ParamEqEditPhase
import ink.lipoly.app.sunrise.drop.AudioCodec
import ink.lipoly.app.sunrise.drop.DropException
import ink.lipoly.app.sunrise.headset.HeadsetPhase
import ink.lipoly.app.sunrise.presentation.hasReadyGaia
import ink.lipoly.app.sunrise.headset.HeadsetClient
import ink.lipoly.app.sunrise.headset.HeadsetState
import ink.lipoly.app.sunrise.presentation.PresentationSession
import ink.lipoly.app.sunrise.settings.UiSettings

@Composable
internal fun AppContent(
    catalog: Catalog,
    client: HeadsetClient?,
    session: PresentationSession,
    missingPermissions: Set<String>,
    onRequestPermissions: () -> Unit,
    settings: UiSettings,
    dynamicColorAvailable: Boolean,
    onSettingsChange: (UiSettings) -> Unit,
) {
    val headsetState = client?.state?.collectAsState()?.value ?: HeadsetState()
    val headsetPresentationState by session.headset.state.collectAsState()
    val catalogState by catalog.state.collectAsState()
    val eqEditor = rememberParamEqEditor(client, headsetState, session.eq, missingPermissions)
    val eqState = eqEditor?.state?.collectAsState()?.value
    val operationState by session.catalog.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var notice by remember { mutableStateOf<String?>(null) }
    var referenceChooserOpen by remember { mutableStateOf(false) }
    var targetChooserOpen by remember { mutableStateOf(false) }
    var previewReferenceUuid by remember { mutableStateOf<String?>(null) }
    val snapshot = catalogState.snapshot
    val audioAddress = headsetState.device?.address?.uppercase()
    val manualReferenceUuid = if (audioAddress == null) previewReferenceUuid
        else settings.referenceProductByAddress[audioAddress]
    val reference = remember(snapshot, headsetState.device?.name, manualReferenceUuid) {
        resolveCatalogReference(snapshot, headsetState.device?.name, manualReferenceUuid)
    }
    val working = headsetPresentationState.working
    val candidates = headsetPresentationState.candidates
    val visibleCandidates = remember(candidates, snapshot, settings.catalogOnlyDevices) {
        if (settings.catalogOnlyDevices) candidates.filter { matchesCatalogDevice(snapshot, it.name) } else candidates
    }
    val codecBlocked = eqState?.isEditing == true ||
        eqState?.phase == ParamEqEditPhase.PENDING || eqState?.phase == ParamEqEditPhase.WRITING

    LaunchedEffect(eqState?.error) {
        eqState?.error?.let { notice = errorMessage(it) }
    }
    LaunchedEffect(catalogState.error) {
        catalogState.error?.let { notice = it }
    }
    LaunchedEffect(notice) {
        notice?.let {
            snackbar.showSnackbar(it)
            notice = null
        }
    }
    LaunchedEffect(headsetPresentationState.notice) {
        headsetPresentationState.notice?.let { expected ->
            snackbar.showSnackbar(expected.message + (expected.error?.let(::errorMessage) ?: ""))
            session.headset.clearNotice(expected)
        }
    }
    LaunchedEffect(operationState.notice) {
        operationState.notice?.let { expected ->
            snackbar.showSnackbar(expected.message + (expected.error?.let(::errorMessage) ?: ""))
            session.catalog.clearNotice(expected)
        }
    }
    LaunchedEffect(snapshot, manualReferenceUuid) {
        if (reference.invalidManualBinding) {
            notice = "绑定的目录 UUID 已不在当前数据库中，已恢复自动匹配；绑定未迁移到其他记录。"
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

    fun chooseTarget(product: CatalogProduct) {
        onSettingsChange(settings.copy(targetProductUuid = product.uuid, showTargetResponse = true))
        targetChooserOpen = false
    }

    fun setCodec(codec: AudioCodec, enabled: Boolean) {
        val latest = eqEditor?.state?.value
        if (latest?.isEditing == true || latest?.phase == ParamEqEditPhase.PENDING ||
            latest?.phase == ParamEqEditPhase.WRITING) return
        session.headset.runAction("设置编码", control = true) {
            gaia.setCodecEnabled(codec, enabled)
            eqEditor?.refresh()
        }
    }

    val navStack = OurNavStack.rememberIt()
    val route = navStack.lastOrNull()
    val title = when (route) {
        OurNavStack.Route.Equalizer -> "参数均衡器"
        OurNavStack.Route.Settings -> "设定"
        OurNavStack.Route.Catalog -> "产品与频响目录"
        OurNavStack.Route.Diagnostics -> "高级诊断"
        else -> "SunRise"
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        if (route == OurNavStack.Route.Catalog || route == OurNavStack.Route.Diagnostics) {
                            TextButton(onClick = { navStack.back() }) { Text("返回") }
                        }
                    },
                    actions = {
                        if (route == OurNavStack.Route.Overview) {
                            TextButton(
                                onClick = { session.headset.refresh("刷新") },
                                enabled = client != null && missingPermissions.isEmpty() && working == null &&
                                    (headsetState.phase != HeadsetPhase.READY || headsetState.controls.hasReadyGaia()),
                            ) { Text("刷新") }
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (route != OurNavStack.Route.Diagnostics) NavigationBar(windowInsets = NavigationBarDefaults.windowInsets) {
                    OurNavStack.navigableNodes.createNavigationBarItems(
                        navStack.activeNode
                    ) { navStack.add(it) }
                }
            }
        ) { padding ->
            NavDisplay(
                modifier = Modifier.fillMaxSize().padding(padding),
                backStack = navStack,
                onBack = { navStack.back() },
                entryProvider = entryProvider {
                    entry<OurNavStack.Route.Overview> {
                        OverviewEntry(
                            headsetState,
                            catalogState,
                            navStack,
                            missingPermissions,
                            onRequestPermissions,
                            session,
                            headsetPresentationState,
                            settings,
                            onSettingsChange,
                            client != null,
                            reference.product?.name,
                            codecBlocked,
                            ::setCodec,
                            Modifier.fillMaxSize(),
                        )
                    }
                    entry<OurNavStack.Route.Equalizer> {
                        EqualizerEntry(
                            editor = eqEditor,
                            state = eqState,
                            enabled = working == null && missingPermissions.isEmpty(),
                            snapshot = snapshot,
                            reference = reference,
                            settings = settings,
                            onChooseReference = {
                                if (snapshot != null) referenceChooserOpen = true
                                else notice = "没有可用数据库，请先在设置中导入或拉取"
                            },
                            onResetReference = ::resetReference,
                            onChooseTarget = {
                                if (snapshot != null) targetChooserOpen = true
                                else notice = "没有可用数据库，请先在设置中导入或拉取"
                            },
                            onClearTarget = { onSettingsChange(settings.copy(targetProductUuid = null)) },
                            onSettingsChange = onSettingsChange,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    entry<OurNavStack.Route.Settings> {
                        SettingsEntry(
                            settings = settings,
                            dynamicAvailable = dynamicColorAvailable,
                            catalogState = catalogState.copy(
                                busy = catalogState.busy || operationState.documentBusy || operationState.importPreview != null,
                                error = operationState.error?.let(::errorMessage) ?: catalogState.error,
                            ),
                            canCancelPull = operationState.pullActive,
                            onPull = session.catalog::pull,
                            onCancelPull = session.catalog::cancelPull,
                            onImport = session.catalog::prepareImport,
                            onExport = session.catalog::export,
                            onOpenCatalog = { navStack.add(OurNavStack.Route.Catalog) },
                            missingPermissions = missingPermissions,
                            clientAvailable = client != null,
                            modifier = Modifier.fillMaxSize(),
                            onChange = onSettingsChange,
                            onRequestPermissions = onRequestPermissions,
                            onOpenDiagnostics = { navStack.add(OurNavStack.Route.Diagnostics) },
                        )
                    }
                    entry<OurNavStack.Route.Catalog> {
                        CatalogEntry(
                            snapshot = snapshot,
                            deviceName = headsetState.device?.name,
                            deviceEqBands = eqState?.confirmed?.bands?.size,
                            onUseReference = {
                                chooseReference(it)
                                navStack.back()
                                if (navStack.lastOrNull() != OurNavStack.Route.Equalizer) {
                                    navStack.add(OurNavStack.Route.Equalizer)
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    entry<OurNavStack.Route.Diagnostics> {
                        DiagnosticsEntry(
                            client = client,
                            missingPermissions = missingPermissions,
                            onRequestPermissions = onRequestPermissions,
                            modifier = Modifier.fillMaxSize(),
                            recentEvents = headsetPresentationState.recentEvents,
                        )
                    }
                }
            )
        }
    }
    if (referenceChooserOpen && snapshot != null) CatalogProductSelector(
        snapshot,
        headsetState.device?.name,
        ::chooseReference,
        {
            referenceChooserOpen = false
        },
    ) {

    }
    if (targetChooserOpen && snapshot != null) CatalogProductSelector(
        snapshot, headsetState.device?.name, ::chooseTarget, { targetChooserOpen = false }, "选择目标频响",
    )
    operationState.importPreview?.let { preview ->
        AlertDialog(
            onDismissRequest = session.catalog::dismissImport,
            title = { Text("替换当前数据库？") },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    CatalogSnapshotDetails(preview)
                    Text("来源信息由导入文件提供。哈希仅验证文件一致性，不代表官方认证；替换而非合并，允许较旧快照。")
                }
            },
            confirmButton = {
                TextButton(onClick = session.catalog::confirmImport) { Text("替换当前数据库") }
            },
            dismissButton = { TextButton(onClick = session.catalog::dismissImport) { Text("取消") } },
        )
    }
    if (headsetPresentationState.chooserOpen) {
        AlertDialog(
            onDismissRequest = session.headset::dismissChooser,
            title = { Text("选择耳机") },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("显示全部")
                        Switch(
                            checked = !settings.catalogOnlyDevices,
                            onCheckedChange = { onSettingsChange(settings.copy(catalogOnlyDevices = !it)) },
                        )
                    }
                    Text("已隐藏 ${candidates.size - visibleCandidates.size} 台；目录匹配不代表协议支持。")
                    if (visibleCandidates.isEmpty()) Text(
                        if (candidates.isEmpty()) "未找到已连接的音频设备"
                        else "没有匹配目录的设备，可切换“显示全部”",
                    )
                    visibleCandidates.forEach { candidate ->
                        val match = matchesCatalogDevice(snapshot, candidate.name)
                        val response = resolveCatalogReference(snapshot, candidate.name, null).response
                        TextButton(
                            onClick = { session.headset.select(candidate, "连接") },
                            enabled = working == null && missingPermissions.isEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column {
                                Text("${candidate.name ?: "未知设备"} · ${candidate.address}")
                                Text((if (match) "目录匹配" else "未收录名称") + " · " + when (response) {
                                    is CatalogResponse.Ready -> "有参考频响"
                                    is CatalogResponse.Unavailable -> "参考无法解析"
                                    null -> "无参考频响"
                                })
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = session.headset::dismissChooser) { Text("稍后") }
            },
        )
    }
}

/** 保留设备读回失配与无法验证的区别，不把发送成功显示为应用成功。 */
internal fun errorMessage(error: Exception): String = when (error) {
    is DropException.Unverified -> "命令已发送，可能已部分应用，但读回未能验证；当前状态未知，请重新读取。"
    is DropException.AncModeMismatch -> "读回不一致；设备实际为 ${error.observed.display()}。"
    is DropException.CodecStateMismatch -> "${error.codec} 读回不一致；设备实际${if (error.observed) "开启" else "关闭"}。"
    else -> error.message ?: "未知错误"
}