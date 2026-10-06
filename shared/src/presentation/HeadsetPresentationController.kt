package ink.lipoly.app.sunrise.presentation

import ink.lipoly.app.sunrise.composeLegacy.OverviewControl
import ink.lipoly.app.sunrise.blueConnector.BtDevice
import ink.lipoly.app.sunrise.composeLegacy.hasReadyGaia
import ink.lipoly.app.sunrise.drop.AudioCodec
import ink.lipoly.app.sunrise.drop.DropException
import ink.lipoly.app.sunrise.drop.GaiaIds
import ink.lipoly.app.sunrise.headset.HeadsetClient
import ink.lipoly.app.sunrise.headset.HeadsetDevice
import ink.lipoly.app.sunrise.headset.HeadsetEvent
import ink.lipoly.app.sunrise.headset.HeadsetPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

internal data class HeadsetPresentationState(
    val candidates: List<HeadsetDevice> = emptyList(),
    val chooserOpen: Boolean = false,
    val confirmedReads: Set<OverviewControl> = emptySet(),
    val working: String? = null,
    val recentEvents: List<HeadsetEvent> = emptyList(),
    val notice: PresentationNotice? = null,
)

internal class HeadsetPresentationController(
    private val coordinator: ConnectionCoordinator,
    scope: CoroutineScope,
) {
    private val client = coordinator.client
    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val ownedScope = CoroutineScope(scope.coroutineContext + job)
    private val mutableState = MutableStateFlow(HeadsetPresentationState())
    val state: StateFlow<HeadsetPresentationState> = mutableState.asStateFlow()
    private var selectionPrompted = false
    private val refreshGeneration = MutableStateFlow(0)
    private var actionJob: Job? = null
    private val guard = Any()
    @kotlin.concurrent.Volatile
    private var closed = false

    init {
        client?.let { active ->
            ownedScope.launch {
                active.events.collect { event ->
                    update { copy(recentEvents = (recentEvents + event).takeLast(10)) }
                }
            }
            ownedScope.launch {
                combine(active.state, coordinator.permissions) { headset, permissions ->
                    headset.phase to (permissions?.isEmpty() == true)
                }.distinctUntilChanged().collectLatest { (phase, permitted) ->
                        if (phase == HeadsetPhase.SELECTION_REQUIRED && permitted) {
                            try {
                                val devices = active.discoverConnectedDevices()
                                update { copy(candidates = devices) }
                                if (!selectionPrompted) {
                                    update { copy(chooserOpen = true) }
                                    selectionPrompted = true
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                notice("发现设备失败：", "Device discovery failed: ", e)
                            }
                        } else if (phase == HeadsetPhase.IDLE || phase == HeadsetPhase.READY) {
                            selectionPrompted = false
                        }
                    }
            }
            ownedScope.launch {
                combine(active.state, coordinator.permissions, refreshGeneration) { headset, permissions, generation ->
                    ReadKey(headset.device?.device, headset.phase, headset.controls.phase, permissions?.isEmpty() == true, generation)
                }.distinctUntilChanged().collectLatest {
                    update { copy(confirmedReads = emptySet()) }
                    if (!it.permitted || it.phase != HeadsetPhase.READY) return@collectLatest
                    val initial = active.state.value
                    if (!initial.controls.hasReadyGaia()) return@collectLatest
                    delay(500.milliseconds)
                    val controls = initial.controls
                    val advertised = controls.capabilities.gaiaFeatures
                    val incomplete = !controls.capabilities.complete
                    for (control in OverviewControl.entries) {
                        val feature = when (control) {
                            OverviewControl.GAIN -> GaiaIds.DAC_GAIN
                            OverviewControl.LED -> GaiaIds.LED
                            OverviewControl.SPATIAL, OverviewControl.HEAD_TRACKING -> GaiaIds.SPATIAL_AUDIO
                        }
                        if (feature !in advertised && !incomplete) continue
                        if (control == OverviewControl.HEAD_TRACKING && feature !in advertised &&
                            OverviewControl.SPATIAL !in mutableState.value.confirmedReads) continue
                        while (mutableState.value.working != null) delay(100.milliseconds)
                        try {
                            val current = active.state.value
                            if (current.phase != HeadsetPhase.READY || !current.controls.hasReadyGaia()) return@collectLatest
                            when (control) {
                                OverviewControl.GAIN -> active.gaia.getGain()
                                OverviewControl.LED -> active.gaia.isLedOn()
                                OverviewControl.SPATIAL -> active.gaia.isSpatialOn()
                                OverviewControl.HEAD_TRACKING -> active.gaia.getHeadTracking()
                            }
                            update { copy(confirmedReads = confirmedReads + control) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // Optional overview reads are independent; a missing capability is not a global error.
                        }
                    }
                }
            }
            ownedScope.launch {
                combine(active.state, coordinator.permissions, refreshGeneration) { headset, permissions, generation ->
                    ReadKey(headset.device?.device, headset.phase, headset.controls.phase, permissions?.isEmpty() == true, generation)
                }.distinctUntilChanged().collectLatest { key ->
                    if (!key.permitted || key.phase != HeadsetPhase.READY) return@collectLatest
                    val initial = active.state.value
                    if (!initial.controls.hasReadyGaia()) return@collectLatest
                    if (GaiaIds.CODEC_TYPE !in initial.controls.capabilities.gaiaFeatures && initial.controls.capabilities.complete) return@collectLatest
                    val bound = try { active.gaia } catch (_: DropException.NotReady) { return@collectLatest }
                    for (codec in AudioCodec.entries) {
                        while (mutableState.value.working != null) delay(100.milliseconds)
                        try {
                            bound.isCodecEnabled(codec)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // Codec support is independent; absent values remain unknown.
                        }
                    }
                }
            }
        }
    }

    fun chooseHeadset(): Unit = synchronized(guard) {
        val active = client ?: return@synchronized
        if (closed || coordinator.permissions.value?.isEmpty() != true || mutableState.value.working != null) return@synchronized
        ownedScope.launch {
            try {
                val devices = active.discoverConnectedDevices()
                update { copy(candidates = devices, chooserOpen = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice("发现设备失败：", "Device discovery failed: ", e)
            }
        }
    }

    fun dismissChooser() {
        update { copy(chooserOpen = false) }
    }

    fun select(candidate: HeadsetDevice, label: String) {
        dismissChooser()
        runAction(label) { connect(candidate) }
    }

    fun retryConnection(): Unit = synchronized(guard) {
        val active = client ?: return@synchronized
        if (closed || coordinator.permissions.value?.isEmpty() != true) return@synchronized
        if (active.state.value.phase == HeadsetPhase.SELECTION_REQUIRED) chooseHeadset() else coordinator.retry()
    }

    fun refresh(label: String): Unit = synchronized(guard) {
        if (closed) return@synchronized
        refreshGeneration.value += 1
        val active = client ?: return@synchronized
        if (coordinator.permissions.value?.isEmpty() != true) return@synchronized
        val snapshot = active.state.value
        when {
            snapshot.phase == HeadsetPhase.READY && snapshot.controls.hasReadyGaia() -> runAction(label, control = true) {
                val current = active.state.value
                var firstFailure: Exception? = null
                if (current.controls.capabilities.ancModes.isNotEmpty()) {
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
            snapshot.phase != HeadsetPhase.READY -> retryConnection()
        }
    }

    fun runAction(label: String, control: Boolean = false, action: suspend HeadsetClient.() -> Unit) {
        synchronized(guard) {
            val active = client ?: return
            if (closed || mutableState.value.working != null || coordinator.permissions.value?.isEmpty() != true) return
            if (control && !active.state.value.let { it.phase == HeadsetPhase.READY && it.controls.hasReadyGaia() }) return
            update { copy(working = label) }
            actionJob = ownedScope.launch {
                try {
                    if (control && !active.state.value.let { it.phase == HeadsetPhase.READY && it.controls.hasReadyGaia() }) {
                        throw DropException.NotReady()
                    }
                    active.action()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    notice("$label：", "$label: ", e)
                } finally {
                    update { copy(working = null) }
                }
            }
        }
    }

    fun clearNotice(expected: PresentationNotice) {
        update {
            if (notice === expected) copy(notice = null) else this
        }
    }

    fun close() = synchronized(guard) {
        if (closed) return@synchronized
        closed = true
        actionJob?.cancel()
        ownedScope.cancel()
        mutableState.update { it.copy(working = null, chooserOpen = false) }
    }

    private fun notice(chinese: String, english: String, error: Exception) {
        update { copy(notice = PresentationNotice(chinese, english, error)) }
    }

    private inline fun update(crossinline transform: HeadsetPresentationState.() -> HeadsetPresentationState) {
        synchronized(guard) {
            if (!closed) mutableState.update { it.transform() }
        }
    }

    private data class ReadKey(
        val device: BtDevice?,
        val phase: HeadsetPhase,
        val controlsPhase: ink.lipoly.app.sunrise.drop.DropPhase,
        val permitted: Boolean,
        val generation: Int,
    )

}
