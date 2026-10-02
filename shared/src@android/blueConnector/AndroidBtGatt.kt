package ink.lipoly.app.sunrise.blueConnector

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal class AndroidBtGatt internal constructor(
    private val device: AndroidBtDevice,
) : BtGatt {
    // A monitor permits synchronous session/manager invalidation; wireless waits never hold it.
    private val lifecycle = Any()
    private val mutableState = MutableStateFlow(GattState())
    override val state = mutableState.asStateFlow()
    private var current: AndroidGattSession? = null
    private var connecting: ConnectionAttempt? = null
    private var closed = false

    private class ConnectionAttempt(
        val session: AndroidGattSession,
        val result: CompletableDeferred<GattSession>,
        val job: Job,
    )

    override suspend fun connect(): GattSession {
        val attempt = synchronized(lifecycle) {
            if (closed || device.owner.isClosed) throw BtException.Disconnected()
            device.owner.requireBluetooth()
            current?.takeIf { mutableState.value.phase == GattPhase.CONNECTED && it.isActive }
                ?.let { return it }
            connecting ?: run {
                val session = AndroidGattSession(device, ::sessionTerminated)
                val result = CompletableDeferred<GattSession>()
                val job = device.owner.scope.launch(start = CoroutineStart.LAZY) {
                    establish(session, result)
                }
                ConnectionAttempt(session, result, job).also {
                    current = session
                    connecting = it
                    publish(GattState(GattPhase.CONNECTING))
                }
            }
        }
        attempt.job.start()
        // This deferred is not parented to callers: cancelling one waiter cannot cancel the radio job.
        return attempt.result.await()
    }

    private suspend fun establish(
        session: AndroidGattSession,
        result: CompletableDeferred<GattSession>,
    ) {
        try {
            session.open()
            synchronized(lifecycle) {
                if (closed || device.owner.isClosed || current !== session || !session.isActive)
                    throw BtException.Disconnected()
                if (connecting?.result === result) connecting = null
                publish(GattState(GattPhase.CONNECTED, session))
                result.complete(session)
            }
        } catch (_: CancellationException) {
            val failure = BtException.Disconnected()
            session.invalidate(failure)
            result.completeExceptionally(failure)
        } catch (error: Throwable) {
            val failure = error as? BtException ?: BtException.Transport("GATT connection failed", error)
            session.invalidate(failure)
            result.completeExceptionally(failure)
        }
    }

    override suspend fun disconnect() {
        val previous = detach(BtException.Disconnected(), closed = false)
        previous.attempt?.job?.cancel()
        previous.session?.invalidate(BtException.Disconnected())
        // A replacement connect may proceed while the previous job finishes cancelling.
        previous.attempt?.job?.join()
    }

    internal fun invalidate(
        cause: BtException = BtException.Disconnected(),
        closed: Boolean = false,
    ) {
        val previous = detach(cause, closed)
        previous.attempt?.job?.cancel()
        previous.session?.invalidate(cause)
    }

    private class Detached(val session: AndroidGattSession?, val attempt: ConnectionAttempt?)

    private fun detach(cause: BtException, closed: Boolean): Detached = synchronized(lifecycle) {
        if (closed || device.owner.isClosed) this.closed = true
        val previous = Detached(current, connecting)
        current = null
        connecting = null
        previous.attempt?.result?.completeExceptionally(cause)
        publish(terminalState(cause))
        previous
    }

    private fun sessionTerminated(session: AndroidGattSession, cause: BtException) {
        synchronized(lifecycle) {
            // An old native callback/session.close cannot change a replacement connection.
            if (current !== session) return
            current = null
            connecting?.takeIf { it.session === session }?.result?.completeExceptionally(cause)
            connecting = null
            publish(terminalState(cause))
        }
    }

    private fun terminalState(cause: BtException): GattState = when {
        closed || device.owner.isClosed -> GattState(GattPhase.CLOSED)
        cause is BtException.Disconnected -> GattState()
        else -> GattState(GattPhase.ERROR, error = cause)
    }

    private fun publish(value: GattState) {
        if (mutableState.value == value) return
        mutableState.value = value
        device.owner.publishGatt(device, value)
    }
}
