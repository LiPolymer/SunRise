package ink.lipoly.app.sunrise.drop

import ink.lipoly.app.sunrise.blueConnector.BtDevice
import ink.lipoly.app.sunrise.blueConnector.GattPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Controls an explicitly connected device without owning its Bluetooth connection. */
class DropController(
    val device: BtDevice,
    private val options: DropOptions = DropOptions(),
    private val profileDevice: BtDevice = device,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lifecycle = Mutex()
    private val closed = MutableStateFlow(false)
    private val binding = MutableStateFlow<DropControlBinding?>(null)
    private val mutableState = MutableStateFlow(DropState())
    private val mutableEvents = MutableSharedFlow<DropEvent>(extraBufferCapacity = 32)
    val state: StateFlow<DropState> = mutableState.asStateFlow()
    val events: SharedFlow<DropEvent> = mutableEvents.asSharedFlow()

    val gaia: GaiaControls get() = readyBinding().gaia
    val source: SourceControls get() = readyBinding().source

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            device.gatt.state.collect { synchronizeBinding()?.startInitialization() }
        }
    }

    /** Initializes only the current GATT session; never scans or connects a device. */
    suspend fun awaitReady() {
        ensureOpen()
        val current = synchronizeBinding()
        ensureOpen()
        if (current == null) throw DropException.NotReady()
        current.startInitialization()
        current.ready.await()
        if (!isCurrent(current) || !current.isOpen) throw DropException.Disconnected()
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        binding.getAndUpdate { null }?.close()
        scope.cancel()
        mutableState.value = DropState()
    }

    private suspend fun synchronizeBinding(): DropControlBinding? = lifecycle.withLock {
        if (closed.value) return@withLock null
        // Use the live snapshot, not an earlier emission that may have been overtaken.
        val gatt = device.gatt.state.value
        val session = gatt.session.takeIf { gatt.phase == GattPhase.CONNECTED }
        val previous = binding.value
        if (session == null) {
            binding.value = null
            previous?.close()
            mutableState.update { if (binding.value == null) DropState() else it }
            return@withLock null
        }
        if (previous != null && previous.session === session && previous.session.id == session.id)
            return@withLock previous

        binding.value = null
        previous?.close()
        val next = DropControlBinding(
            session = session,
            parentScope = scope,
            resolvedProfile = DropProfiles.resolve(options, profileDevice.address, profileDevice.info.value.name),
            isCurrent = ::isCurrent,
            publishState = ::publishState,
            publishEvent = ::publishEvent,
        )
        binding.value = next
        // close() is synchronous and deliberately does not wait for this mutex.
        if (closed.value || !isCurrent(next)) {
            binding.compareAndSet(next, null)
            next.close()
            mutableState.update { if (closed.value || binding.value == null) DropState() else it }
            return@withLock null
        }
        publishState(next)
        next
    }

    private fun isCurrent(candidate: DropControlBinding): Boolean {
        val gatt = device.gatt.state.value
        return !closed.value && binding.value === candidate && gatt.phase == GattPhase.CONNECTED &&
            gatt.session === candidate.session && gatt.session.id == candidate.session.id
    }

    private fun publishState(candidate: DropControlBinding) {
        mutableState.update { current -> if (isCurrent(candidate)) candidate.state.value else current }
    }

    private fun publishEvent(candidate: DropControlBinding, event: DropEvent) {
        if (isCurrent(candidate)) mutableEvents.tryEmit(event)
    }

    private fun readyBinding(): DropControlBinding {
        ensureOpen()
        val current = binding.value
        if (current == null || !isCurrent(current) || !current.isOpen ||
            current.state.value.phase != DropPhase.READY) throw DropException.NotReady()
        return current
    }

    private fun ensureOpen() {
        if (closed.value) throw DropException.Disconnected()
    }
}
