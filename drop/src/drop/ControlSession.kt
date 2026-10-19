package ink.lipoly.app.sunrise.drop

import ink.lipoly.app.sunrise.bluetooth.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One GATT epoch and one transaction gate shared by all installed protocol families. */
internal class ControlSession(
    val session: GattSession,
    parentScope: CoroutineScope,
    protocols: Set<DropProtocol>,
    private val current: () -> Boolean,
    private val publishState: (DropState) -> Unit,
    private val publishEvent: (DropEvent) -> Unit,
) {
    private val lifetime = Job(parentScope.coroutineContext[Job])
    val scope = CoroutineScope(SupervisorJob(parentScope.coroutineContext[Job]) + Dispatchers.Default)
    private val closed = MutableStateFlow(false)
    private val transactions = Mutex()
    private val closeHandlers = MutableStateFlow<List<() -> Unit>?>(emptyList())
    private val mutableState = MutableStateFlow(DropState(phase = DropPhase.PROBING, protocols = protocols))
    val state = mutableState.asStateFlow()
    val isOpen: Boolean get() = !closed.value && lifetime.isActive && current()

    private class RetiredCancellation : CancellationException("Drop session disconnected")

    init { lifetime.invokeOnCompletion { close() } }

    fun checkActive() {
        if (!isOpen) throw DropException.Disconnected()
    }

    fun mutate(block: (DropState) -> DropState) {
        checkActive()
        mutableState.update { if (isOpen) block(it) else it }
        if (isOpen) publishState(mutableState.value)
        checkActive()
    }

    fun emit(event: DropEvent) {
        checkActive()
        publishEvent(event)
    }

    fun characteristic(service: String, uuid: String): GattCharacteristic? =
        session.services.firstOrNull { it.uuid == service }?.characteristics?.firstOrNull { it.uuid == uuid }

    fun requireReady(probing: Boolean) {
        checkActive()
        if (state.value.phase != DropPhase.READY && !(probing && state.value.phase == DropPhase.PROBING))
            throw DropException.NotReady()
    }

    /** Retirement cancels only this call's child Job; caller cancellation stays CancellationException. */
    suspend fun <T> transaction(block: suspend () -> T): T = try {
        coroutineScope {
            val call = currentCoroutineContext().job
            val listener = lifetime.invokeOnCompletion { call.cancel(RetiredCancellation()) }
            try {
                checkActive()
                transactions.withLock {
                    checkActive()
                    block().also { checkActive() }
                }
            } finally {
                listener.dispose()
            }
        }
    } catch (_: RetiredCancellation) {
        currentCoroutineContext().ensureActive()
        throw DropException.Disconnected()
    } catch (error: BtException) {
        throw normalizeTransport(error)
    }

    suspend fun optionalOperation(block: suspend () -> Unit) {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (!isOpen) throw normalizeTransport(error)
        }
        checkActive()
    }

    /** Registration races safely with retirement: a late handler is invoked immediately. */
    fun onClose(handler: () -> Unit) {
        while (true) {
            val handlers = closeHandlers.value
            if (handlers == null) {
                handler()
                return
            }
            if (closeHandlers.compareAndSet(handlers, handlers + handler)) return
        }
    }

    /** Invalidate first, fail pending, then cancel calls/collectors; never close borrowed GATT. */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        closeHandlers.getAndUpdate { null }?.forEach { it() }
        lifetime.cancel(RetiredCancellation())
        scope.cancel(RetiredCancellation())
    }

    fun normalizeTransport(error: Exception): DropException = when (error) {
        is DropException -> error
        is BtException.Timeout -> DropException.Timeout(error.operation)
        is BtException.Disconnected -> DropException.Disconnected()
        is BtException.UnsupportedOperation -> DropException.UnsupportedCapability(error.operation)
        is BtException.InvalidGattHandle -> DropException.UnsupportedCapability("GATT characteristic")
        is BtException.PacketTooLarge -> DropException.Protocol(error.message ?: "Packet exceeds negotiated GATT MTU")
        else -> DropException.Transport(error.message ?: "Drop GATT operation failed", error)
    }
}
