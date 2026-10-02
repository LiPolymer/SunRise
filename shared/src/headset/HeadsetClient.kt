package ink.lipoly.app.sunrise.headset

import ink.lipoly.app.sunrise.blueConnector.BtDevice
import ink.lipoly.app.sunrise.blueConnector.BtDeviceKind
import ink.lipoly.app.sunrise.blueConnector.BtEvent
import ink.lipoly.app.sunrise.blueConnector.BtException
import ink.lipoly.app.sunrise.blueConnector.BtManager
import ink.lipoly.app.sunrise.blueConnector.GattPhase
import ink.lipoly.app.sunrise.blueConnector.GattSession
import ink.lipoly.app.sunrise.drop.DropController
import ink.lipoly.app.sunrise.drop.DropException
import ink.lipoly.app.sunrise.drop.DropOptions
import ink.lipoly.app.sunrise.drop.DropPhase
import ink.lipoly.app.sunrise.drop.DropProtocol
import ink.lipoly.app.sunrise.drop.DropState
import ink.lipoly.app.sunrise.drop.GaiaControls
import ink.lipoly.app.sunrise.drop.SourceControls
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Application-owned selection, connection lifetime, and polling for a single audio headset. */
class HeadsetClient internal constructor(
    private val bt: BtManager,
    private val associations: HeadsetAssociations,
    private val options: DropOptions = DropOptions(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lifecycle = Mutex()
    private val closed = MutableStateFlow(false)
    private val connection = MutableStateFlow<Connection?>(null)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val mutableState = MutableStateFlow(HeadsetState())
    private val mutableEvents = MutableSharedFlow<HeadsetEvent>(extraBufferCapacity = 32)
    val state: StateFlow<HeadsetState> = mutableState.asStateFlow()
    val events: SharedFlow<HeadsetEvent> = mutableEvents.asSharedFlow()

    val gaia: GaiaControls get() = readyController().gaia
    val source: SourceControls get() = readyController().source

    // Identity is the facade epoch. Each replacement waits for its predecessor's cleanup,
    // including when an intervening replacement was cancelled before starting radio work.
    private class Connection(
        val auto: Boolean,
        selected: HeadsetDevice?,
        parent: Job?,
        val first: CompletableDeferred<Unit>? = null,
    ) {
        val job = Job(parent)
        val finished = CompletableDeferred<Unit>()
        val target = MutableStateFlow(selected)
        val attempt = MutableStateFlow<Attempt?>(null)
    }

    private class Attempt(val endpoint: BtDevice, val controller: DropController) {
        val session = MutableStateFlow<GattSession?>(null)
        val adopted = MutableStateFlow(false)
        val observers = mutableListOf<Job>()
        var ownsGatt = false
    }

    private class AudioTargetGone : CancellationException("Audio headset is no longer connected")

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            bt.events.collect { event ->
                val changed = when (event) {
                    is BtEvent.OnDeviceChanged -> event.device
                    is BtEvent.OnDiscovered -> event.device
                    else -> null
                }
                if (changed != null) updateDeviceSnapshot(changed)
                wake.trySend(Unit)
            }
        }
    }

    suspend fun discoverConnectedDevices(): List<HeadsetDevice> {
        ensureOpen()
        return bt.refreshConnectedAudioDevices().map(::snapshot)
    }

    fun startAutoConnect() {
        ensureOpen()
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val (next, previous) = replaceConnection(auto = true, selected = null, first = null)
                launchConnection(next, previous)
            } catch (e: DropException.Disconnected) {
                if (!closed.value) throw e
            }
        }
    }

    suspend fun connect(address: String) {
        ensureOpen()
        connect(snapshot(bt.device(address)))
    }

    suspend fun connect(device: HeadsetDevice) {
        ensureOpen()
        val first = CompletableDeferred<Unit>()
        val selected = device.copy(verified = associations.endpoint(device.address.uppercase()) != null)
        val (next, previous) = replaceConnection(auto = false, selected = selected, first = first)
        launchConnection(next, previous)
        try {
            first.await()
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                lifecycle.withLock {
                    if (connection.value === next) {
                        connection.value = null
                        mutableState.value = HeadsetState()
                    }
                    next.job.cancel()
                    next.attempt.value?.controller?.close()
                }
                next.finished.await()
            }
            throw e
        }
    }

    suspend fun disconnect() {
        val previous = lifecycle.withLock {
            connection.getAndUpdate { null }.also {
                it?.job?.cancel()
                it?.attempt?.value?.controller?.close()
                mutableState.value = HeadsetState()
            }
        }
        withContext(NonCancellable) { previous?.finished?.await() }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        val previous = connection.getAndUpdate { null }
        previous?.job?.cancel()
        previous?.attempt?.value?.controller?.close()
        mutableState.value = HeadsetState()
        wake.close()
        scope.cancel()
    }

    private suspend fun replaceConnection(
        auto: Boolean,
        selected: HeadsetDevice?,
        first: CompletableDeferred<Unit>?,
    ): Pair<Connection, Connection?> = lifecycle.withLock {
        ensureOpen()
        val next = Connection(auto, selected, scope.coroutineContext[Job], first)
        val previous = connection.getAndUpdate { next }
        previous?.job?.cancel()
        previous?.attempt?.value?.controller?.close()
        publishLocked(next) {
            HeadsetState(
                phase = if (auto) HeadsetPhase.DISCOVERING else HeadsetPhase.CONNECTING,
                device = selected,
            )
        }
        // Synchronous close does not wait for this mutex.
        if (closed.value) {
            connection.compareAndSet(next, null)
            next.job.cancel()
        }
        next to previous
    }

    private fun launchConnection(next: Connection, previous: Connection?) {
        // UNDISPATCHED entry installs the cleanup finally even if close cancelled next.job
        // between replacement and launch. No radio operation or join runs under lifecycle.
        CoroutineScope(scope.coroutineContext + next.job).launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                withContext(NonCancellable) { previous?.finished?.await() }
                ensureCurrent(next)
                if (next.auto) autoLoop(next) else connectionLoop(next, retryFirst = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report(next, e)
                next.first?.completeExceptionally(e)
            } finally {
                try {
                    withContext(NonCancellable) {
                        next.attempt.value?.let { releaseAttempt(next, it) }
                    }
                } finally {
                    next.first?.completeExceptionally(DropException.Disconnected())
                    next.finished.complete(Unit)
                    next.job.complete()
                }
            }
        }
    }

    private suspend fun autoLoop(current: Connection) {
        while (currentCoroutineContext().isActive && isCurrent(current)) {
            try {
                publish(current) { HeadsetState(phase = HeadsetPhase.DISCOVERING) }
                val candidates = discoverConnectedDevices()
                ensureCurrent(current)
                when (candidates.size) {
                    0 -> publish(current) { HeadsetState() }
                    1 -> {
                        current.target.value = candidates.single()
                        connectionLoop(current, retryFirst = true)
                        current.target.value = null
                        publish(current) { HeadsetState() }
                    }
                    else -> publish(current) { HeadsetState(phase = HeadsetPhase.SELECTION_REQUIRED) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report(current, e)
            }
            awaitWake(5_000)
        }
    }

    private suspend fun connectionLoop(current: Connection, retryFirst: Boolean) {
        try {
            coroutineScope {
                val selected = current.target.value ?: return@coroutineScope
                val targetJob = currentCoroutineContext()[Job]!!
                val audioObserver = if (current.auto) launch(start = CoroutineStart.UNDISPATCHED) {
                    bt.connectedAudioDevices.first { devices ->
                        devices.none { it.address.equals(selected.address, ignoreCase = true) }
                    }
                    targetJob.cancel(AudioTargetGone())
                } else null
                var everReady = false
                try {
                    while (currentCoroutineContext().isActive && isCurrent(current)) {
                        val target = current.target.value ?: break
                        if (current.auto && discoverConnectedDevices().none {
                                it.address.equals(target.address, ignoreCase = true)
                            }) break
                        publish(current) {
                            HeadsetState(
                                phase = if (everReady) HeadsetPhase.RECONNECTING else HeadsetPhase.CONNECTING,
                                device = current.target.value ?: target,
                            )
                        }
                        var active: Attempt? = null
                        try {
                            val ready = openSelected(current, target)
                            active = ready
                            everReady = true
                            current.first?.complete(Unit)
                            startPolling(current, ready)
                            val lost = ready.endpoint.gatt.state.first {
                                it.phase != GattPhase.CONNECTED || it.session !== ready.session.value
                            }
                            lost.error?.let { throw it }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            active?.let { releaseAttempt(current, it) }
                            active = null
                            report(current, e)
                            if (!everReady && !retryFirst) {
                                current.first?.completeExceptionally(e)
                                current.target.value = null
                                return@coroutineScope
                            }
                        } finally {
                            active?.let { releaseAttempt(current, it) }
                        }
                        awaitWake(4_000)
                    }
                } finally {
                    audioObserver?.cancel()
                }
            }
        } catch (e: AudioTargetGone) {
            currentCoroutineContext().ensureActive()
        } finally {
            current.first?.completeExceptionally(DropException.Disconnected())
        }
    }

    private suspend fun openSelected(current: Connection, selected: HeadsetDevice): Attempt {
        val tried = mutableSetOf<String>()
        var lastFailure: Exception? = null
        var unsupported: DropException.UnsupportedDevice? = null
        suspend fun tryCandidate(endpoint: BtDevice): Attempt? {
            if (!tried.add(endpoint.address.uppercase())) return null
            return try {
                tryEndpoint(current, endpoint, selected)
            } catch (e: CancellationException) {
                throw e
            } catch (e: DropException.UnsupportedDevice) {
                unsupported = e
                null
            } catch (e: Exception) {
                lastFailure = e
                null
            }
        }

        associations.endpoint(selected.address.uppercase())?.let { address ->
            val cached = try {
                bt.device(address)
            } catch (_: BtException.InvalidDevice) {
                // Old preferences may contain malformed addresses; they are not candidates.
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastFailure = e
                null
            }
            cached?.let { tryCandidate(it)?.let { ready -> return ready } }
        }
        tryCandidate(selected.device)?.let { return it }
        if (!selected.name.isNullOrBlank()) {
            val bonded = try {
                bt.bondedDevices()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastFailure = e
                emptyList()
            }
            for (endpoint in bonded) {
                val info = endpoint.info.value
                if ((info.kind == BtDeviceKind.LE || info.kind == BtDeviceKind.DUAL) &&
                    info.name?.equals(selected.name, ignoreCase = true) == true) {
                    tryCandidate(endpoint)?.let { return it }
                }
            }
        }
        val scanned = try {
            bt.scanLe(name = selected.name, address = selected.address)
        } catch (e: CancellationException) {
            throw e
        } catch (_: BtException.MissingPermission) {
            // A missing scan grant does not invalidate direct GATT attempts.
            emptyList()
        } catch (e: Exception) {
            lastFailure = e
            emptyList()
        }
        for (endpoint in scanned) tryCandidate(endpoint)?.let { return it }
        // Unsupported is conclusive only when every attempted connection had that result.
        throw lastFailure ?: unsupported ?: DropException.Disconnected()
    }

    private suspend fun tryEndpoint(
        current: Connection,
        endpoint: BtDevice,
        selected: HeadsetDevice,
    ): Attempt {
        ensureCurrent(current)
        val attempt = Attempt(endpoint, DropController(endpoint, options, profileDevice = selected.device))
        var adopted = false
        try {
            lifecycle.withLock {
                ensureCurrent(current)
                current.attempt.value = attempt
                publishLocked(current) {
                    it.copy(
                        phase = if (it.phase == HeadsetPhase.RECONNECTING) it.phase else HeadsetPhase.CONNECTING,
                        controls = DropState(), error = null,
                    )
                }
            }
            val observers = CoroutineScope(currentCoroutineContext())
            attempt.observers += observers.launch(start = CoroutineStart.UNDISPATCHED) {
                attempt.controller.state.collect { forwardState(current, attempt) }
            }
            attempt.observers += observers.launch(start = CoroutineStart.UNDISPATCHED) {
                attempt.controller.events.collect { event ->
                    lifecycle.withLock {
                        if (isCurrentAttempt(current, attempt) &&
                            (attempt.session.value == null || hasSession(attempt))) {
                            mutableEvents.tryEmit(HeadsetEvent.Control(event))
                        }
                    }
                }
            }
            ensureCurrent(current)
            attempt.ownsGatt = true
            attempt.session.value = endpoint.gatt.connect()
            attempt.controller.awaitReady()
            lifecycle.withLock {
                ensureCurrent(current)
                if (!hasSession(attempt) || attempt.controller.state.value.phase != DropPhase.READY)
                    throw DropException.Disconnected()
                associations.remember(selected.address.uppercase(), endpoint.address)
                attempt.adopted.value = true
                current.target.value = (current.target.value ?: selected).copy(verified = true)
                publishLocked(current) {
                    HeadsetState(
                        phase = HeadsetPhase.READY,
                        device = current.target.value,
                        controls = attempt.controller.state.value,
                    )
                }
            }
            adopted = true
            return attempt
        } finally {
            if (!adopted) releaseAttempt(current, attempt)
        }
    }

    private suspend fun releaseAttempt(current: Connection, attempt: Attempt) = withContext(NonCancellable) {
        lifecycle.withLock {
            if (current.attempt.compareAndSet(attempt, null)) {
                publishLocked(current) {
                    it.copy(
                        phase = if (it.phase == HeadsetPhase.READY || it.phase == HeadsetPhase.PROBING)
                            HeadsetPhase.RECONNECTING else it.phase,
                        controls = DropState(),
                    )
                }
            }
            attempt.controller.close()
            attempt.observers.forEach { it.cancel() }
        }
        if (attempt.ownsGatt) {
            // connect's caller cancellation does not cancel the manager's independent radio job.
            attempt.endpoint.gatt.disconnect()
        }
    }

    private suspend fun forwardState(current: Connection, attempt: Attempt) {
        lifecycle.withLock {
            if (!isCurrentAttempt(current, attempt)) return@withLock
            val controls = attempt.controller.state.value
            if (controls.phase != DropPhase.IDLE && attempt.session.value != null && !hasSession(attempt))
                return@withLock
            publishLocked(current) {
                it.copy(
                    phase = when (controls.phase) {
                        DropPhase.PROBING -> HeadsetPhase.PROBING
                        DropPhase.READY -> if (attempt.adopted.value) HeadsetPhase.READY else HeadsetPhase.PROBING
                        DropPhase.ERROR -> HeadsetPhase.ERROR
                        else -> if (attempt.adopted.value) HeadsetPhase.RECONNECTING else it.phase
                    },
                    controls = controls,
                    error = controls.error,
                )
            }
        }
    }

    private fun startPolling(current: Connection, attempt: Attempt) {
        val polling = CoroutineScope(current.job + Dispatchers.Default)
        attempt.observers += polling.launch {
            if (attempt.controller.state.value.capabilities.ancModes.isNotEmpty()) {
                try {
                    if (isCurrentAttempt(current, attempt) && hasSession(attempt)) attempt.controller.gaia.getAncMode()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Leave an unknown or previously observed mode intact when the initial read fails.
                }
            }
        }
        attempt.observers += polling.launch {
            while (isCurrentAttempt(current, attempt) && hasSession(attempt) &&
                attempt.controller.state.value.phase == DropPhase.READY) {
                if (DropProtocol.GAIA_BLE in attempt.controller.state.value.protocols) {
                    try {
                        attempt.controller.gaia.getBattery()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Polling cannot replace the last observed battery with a fabricated value.
                    }
                }
                delay(30_000)
            }
        }
    }

    private suspend fun updateDeviceSnapshot(device: BtDevice) {
        lifecycle.withLock {
            val current = connection.value ?: return@withLock
            val selected = current.target.value ?: return@withLock
            if (!selected.address.equals(device.address, ignoreCase = true)) return@withLock
            val updated = selected.copy(name = device.info.value.name)
            current.target.value = updated
            publishLocked(current) { previous ->
                if (previous.device?.address.equals(selected.address, ignoreCase = true))
                    previous.copy(device = updated.copy(verified = previous.device?.verified ?: updated.verified))
                else previous
            }
        }
    }

    private fun snapshot(device: BtDevice) = HeadsetDevice(
        device, device.info.value.name, associations.endpoint(device.address.uppercase()) != null,
    )

    private fun hasSession(attempt: Attempt): Boolean {
        val gatt = attempt.endpoint.gatt.state.value
        val session = attempt.session.value
        return session != null && gatt.phase == GattPhase.CONNECTED && gatt.session === session
    }

    private fun readyController(): DropController {
        val current = connection.value ?: throw DropException.NotReady()
        val attempt = current.attempt.value ?: throw DropException.NotReady()
        if (!isCurrentAttempt(current, attempt) || !attempt.adopted.value || !hasSession(attempt) ||
            state.value.phase != HeadsetPhase.READY || state.value.controls.phase != DropPhase.READY ||
            attempt.controller.state.value.phase != DropPhase.READY) throw DropException.NotReady()
        return attempt.controller
    }

    private fun isCurrent(current: Connection) = !closed.value && connection.value === current
    private fun isCurrentAttempt(current: Connection, attempt: Attempt) =
        isCurrent(current) && current.attempt.value === attempt

    private suspend fun publish(current: Connection, change: (HeadsetState) -> HeadsetState) {
        lifecycle.withLock { publishLocked(current, change) }
    }

    private fun publishLocked(current: Connection, change: (HeadsetState) -> HeadsetState) {
        if (isCurrent(current)) mutableState.value = change(mutableState.value)
        // close may have invalidated the epoch without waiting for lifecycle.
        if (closed.value) mutableState.value = HeadsetState()
    }

    private suspend fun report(current: Connection, cause: Exception) {
        lifecycle.withLock {
            if (!isCurrent(current)) return@withLock
            publishLocked(current) { it.copy(phase = HeadsetPhase.ERROR, error = cause) }
            if (isCurrent(current)) mutableEvents.tryEmit(HeadsetEvent.Error(cause))
        }
    }

    private fun ensureOpen() {
        if (closed.value) throw DropException.Disconnected()
    }

    private suspend fun ensureCurrent(current: Connection) {
        currentCoroutineContext().ensureActive()
        if (!isCurrent(current)) throw CancellationException("Headset selection changed")
    }

    private suspend fun awaitWake(timeoutMillis: Long) {
        // Discard the coalesced notifications caused by the attempt that just finished.
        wake.tryReceive()
        withTimeoutOrNull(timeoutMillis) { wake.receiveCatching() }
    }
}
