package ink.lipoly.app.sunrise.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import ink.lipoly.app.sunrise.drop.DropClient
import ink.lipoly.app.sunrise.drop.DropDevice
import ink.lipoly.app.sunrise.drop.DropEvent
import ink.lipoly.app.sunrise.drop.DropException
import ink.lipoly.app.sunrise.drop.DropPhase
import ink.lipoly.app.sunrise.drop.DropState
import ink.lipoly.app.sunrise.drop.GaiaIds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

internal enum class MainPage { OVERVIEW, SETTINGS }

internal class AppNavigationState {
    var page by mutableStateOf(MainPage.OVERVIEW)
    var diagnostics by mutableStateOf(false)
}

@Composable
internal fun rememberAppNavigationState(): AppNavigationState = remember { AppNavigationState() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppContent(
    client: DropClient?,
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
        val state = client?.state?.collectAsState()?.value ?: DropState()
        val scope = rememberCoroutineScope()
        val snackbar = remember { SnackbarHostState() }
        var candidates by remember(client) { mutableStateOf<List<DropDevice>>(emptyList()) }
        var recentEvents by remember(client) { mutableStateOf<List<DropEvent>>(emptyList()) }
        var confirmedReads by remember(client) { mutableStateOf<Set<OverviewControl>>(emptySet()) }
        var working by remember(client) { mutableStateOf<String?>(null) }
        var notice by remember { mutableStateOf<String?>(null) }
        var refreshGeneration by remember { mutableIntStateOf(0) }
        val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

        LaunchedEffect(notice) {
            notice?.let {
                snackbar.showSnackbar(it)
                notice = null
            }
        }
        LaunchedEffect(client) {
            client?.events?.collect { event ->
                recentEvents = (recentEvents + event).takeLast(10)
            }
        }
        LaunchedEffect(client, missingPermissions.isEmpty()) {
            if (client != null && missingPermissions.isEmpty()) client.startAutoConnect()
        }
        LaunchedEffect(client, state.phase, missingPermissions.isEmpty()) {
            if (client != null && missingPermissions.isEmpty() && state.phase == DropPhase.SELECTION_REQUIRED) {
                try {
                    candidates = client.discoverConnectedDevices()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    notice = tr(english, "发现设备失败：", "Device discovery failed: ") + errorMessage(e, english)
                }
            } else if (state.phase != DropPhase.SELECTION_REQUIRED) {
                candidates = emptyList()
            }
        }
        LaunchedEffect(client, state.phase, state.device?.address, refreshGeneration) {
            confirmedReads = emptySet()
            if (client == null || !state.hasReadyGaia()) return@LaunchedEffect
            delay(500.milliseconds) // Let the client's initial battery/ANC reads run first.
            val advertised = state.capabilities.gaiaFeatures
            val incomplete = !state.capabilities.complete
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

        fun runAction(label: String, action: suspend DropClient.() -> Unit) {
            val activeClient = client ?: return
            if (working != null || missingPermissions.isNotEmpty()) return
            working = label
            scope.launch {
                try {
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

        val title = when {
            diagnostics -> tr(english, "高级诊断", "Advanced diagnostics")
            page == MainPage.OVERVIEW -> tr(english, "概览", "Overview")
            else -> tr(english, "设置", "Settings")
        }
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                LargeTopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        if (diagnostics) TextButton(onClick = { navigation.diagnostics = false }) {
                            Text(tr(english, "返回", "Back"))
                        }
                    },
                    actions = {
                        if (!diagnostics && page == MainPage.OVERVIEW) {
                            TextButton(onClick = {
                                refreshGeneration++
                                if (state.hasReadyGaia()) {
                                    runAction(tr(english, "刷新", "Refresh")) {
                                        var firstFailure: Exception? = null
                                        if (state.capabilities.ancModes.isNotEmpty()) {
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
                                } else if (state.phase != DropPhase.READY && client != null && missingPermissions.isEmpty()) {
                                    client.startAutoConnect()
                                }
                            }, enabled = client != null && working == null) { Text(tr(english, "刷新", "Refresh")) }
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
            bottomBar = {
                if (!diagnostics) NavigationBar {
                    NavigationBarItem(
                        selected = page == MainPage.OVERVIEW,
                        onClick = { navigation.page = MainPage.OVERVIEW },
                        icon = { Text("◉") },
                        label = { Text(tr(english, "概览", "Overview")) },
                    )
                    NavigationBarItem(
                        selected = page == MainPage.SETTINGS,
                        onClick = { navigation.page = MainPage.SETTINGS },
                        icon = { Text("⚙") },
                        label = { Text(tr(english, "设置", "Settings")) },
                    )
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            when {
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
                    confirmedReads = confirmedReads,
                    working = working,
                    modifier = Modifier.fillMaxSize().padding(padding),
                    onRequestPermissions = onRequestPermissions,
                    onRetry = { client?.startAutoConnect() },
                    onAnc = { mode -> runAction(tr(english, "设置降噪", "Set ANC")) { gaia.setAncMode(mode) } },
                    onGain = { gain -> runAction(tr(english, "设置增益", "Set gain")) { gaia.setGain(gain) } },
                    onLed = { enabled -> runAction("LED") { gaia.setLedOn(enabled) } },
                    onSpatial = { enabled -> runAction(tr(english, "空间音频", "Spatial audio")) { gaia.setSpatialOn(enabled) } },
                    onTracking = { mode -> runAction(tr(english, "头部追踪", "Head tracking")) { gaia.setHeadTracking(mode) } },
                )
                else -> SettingsScreen(
                    settings = settings,
                    english = english,
                    dynamicAvailable = dynamicColorAvailable,
                    missingPermissions = missingPermissions,
                    clientAvailable = client != null,
                    modifier = Modifier.fillMaxSize().padding(padding),
                    onChange = onSettingsChange,
                    onRequestPermissions = onRequestPermissions,
                    onOpenDiagnostics = { navigation.diagnostics = true },
                )
            }
        }

        if (state.phase == DropPhase.SELECTION_REQUIRED && candidates.isNotEmpty()) {
            AlertDialog(
                onDismissRequest = { candidates = emptyList() },
                title = { Text(tr(english, "选择耳机", "Choose a headset")) },
                text = { Column {
                    candidates.forEach { candidate ->
                        TextButton(onClick = {
                            candidates = emptyList()
                            runAction(tr(english, "连接", "Connect")) { connect(candidate) }
                        }) {
                            Text("${candidate.name ?: tr(english, "未知设备", "Unknown device")} · ${candidate.address}")
                        }
                    }
                } },
                confirmButton = {
                    TextButton(onClick = { candidates = emptyList() }) {
                        Text(tr(english, "稍后", "Later"))
                    }
                },
            )
        }
}

private fun errorMessage(error: Exception, english: Boolean): String = when (error) {
    is DropException.Unverified -> tr(
        english,
        "命令已发送，但读回未能验证；当前状态未知，可刷新重试。",
        "Command sent, but readback could not verify it. Current state is unknown; refresh to retry.",
    )
    is DropException.AncModeMismatch -> tr(
        english,
        "读回不一致；设备实际为 ${error.observed.display(false)}。",
        "Readback mismatch; device reports ${error.observed.display(true)}.",
    )
    else -> error.message ?: tr(english, "未知错误", "Unknown error")
}
