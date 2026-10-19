package ink.lipoly.app.sunrise.drop.source

import ink.lipoly.app.sunrise.bluetooth.GattEvent
import ink.lipoly.app.sunrise.bluetooth.GattProperty
import ink.lipoly.app.sunrise.drop.ControlSession
import ink.lipoly.app.sunrise.drop.DropEvent
import ink.lipoly.app.sunrise.drop.DropException
import ink.lipoly.app.sunrise.drop.DropState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

/** 9ECA wire state for one shared transport epoch; owns no transport resources. */
internal class SourceBinding(private val control: ControlSession) : SourceControlSession {
    private val command = control.characteristic(SourceGattIds.SOURCE_SERVICE, SourceGattIds.SOURCE_COMMAND)
    private val response = control.characteristic(SourceGattIds.SOURCE_SERVICE, SourceGattIds.SOURCE_RESPONSE)
    private val notification = control.characteristic(SourceGattIds.SOURCE_SERVICE, SourceGattIds.SOURCE_NOTIFICATION)
    private val capability = control.characteristic(SourceGattIds.SOURCE_SERVICE, SourceGattIds.SOURCE_CAPABILITY)
        ?.takeIf { GattProperty.READ in it.properties }
    private val info = control.characteristic(SourceGattIds.SOURCE_SERVICE, SourceGattIds.SOURCE_INFO)
        ?.takeIf { GattProperty.READ in it.properties }
    private val closed = MutableStateFlow(false)
    private val pendingSource = MutableStateFlow<PendingSource?>(null)
    private var sequence = 0
    private var collector: Job? = null

    override val state get() = control.state
    val controls: SourceControls = SourceControlsImpl(this)
    var complete: Boolean = false
        private set

    private data class PendingSource(
        val commandId: Int,
        val sequence: Int,
        val reply: CompletableDeferred<SourceFrame>,
    )

    init {
        control.onClose { close() }
    }

    private fun checkActive() {
        control.checkActive()
        if (closed.value) throw DropException.Disconnected()
    }

    /** The raw collector must be listening before enabling either CCCD. */
    suspend fun subscribe() {
        checkActive()
        if (command == null || response == null) throw DropException.UnsupportedCapability("9ECA")
        if (collector != null) return
        collector = control.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            control.session.events.collect { event ->
                if (event is GattEvent.ValueChanged && control.isOpen && !closed.value &&
                    (event.characteristic === response || event.characteristic === notification)) {
                    try {
                        onSource(event.value)
                    } catch (_: DropException.Disconnected) {
                        // Retirement can overtake decoding; a retired frame cannot publish.
                    }
                }
            }
        }
        control.transaction {
            checkActive()
            control.session.setNotifications(response, true)
            notification?.let { characteristic ->
                control.optionalOperation { control.session.setNotifications(characteristic, true) }
            }
            checkActive()
        }
    }

    /** Prefer readable info; command fallback is nonfatal only while this epoch remains active. */
    suspend fun initialize() {
        checkActive()
        val firmware = try {
            SourceCodec.firmware(readSourceInfo(probing = true))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            checkProbeFailure(e)
            try {
                val reply = requestSource(SourceIds.GET_FW_VERSION, byteArrayOf(), probing = true)
                if (SourceCodec.status(reply) == 0) SourceCodec.firmware(reply, 1) else null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                checkProbeFailure(e)
                null
            }
        }
        checkActive()
        if (firmware != null) {
            mutate { it.copy(capabilities = it.capabilities.copy(sourceFeatures = firmware.features)) }
            complete = true
        }
    }

    private fun checkProbeFailure(error: Exception) {
        if (!control.isOpen || closed.value) throw normalizeTransport(error)
        control.checkActive()
    }

    /** Fail only this family's pending reply and collector; the owner closes the shared session. */
    fun close() {
        if (!closed.compareAndSet(expect = false, update = true)) return
        pendingSource.value?.reply?.completeExceptionally(DropException.Disconnected())
        collector?.cancel()
    }

    override fun mutate(block: (DropState) -> DropState) {
        checkActive()
        control.mutate(block)
        checkActive()
    }

    override fun normalizeTransport(error: Exception): DropException = control.normalizeTransport(error)

    override suspend fun requestSource(commandId: Int, payload: ByteArray): ByteArray =
        requestSource(commandId, payload, probing = false)

    /** Install before write; match command and wrapping sequence; clear by identity on every exit. */
    private suspend fun requestSource(commandId: Int, payload: ByteArray, probing: Boolean): ByteArray =
        control.transaction {
            requireReady(probing)
            val pending = PendingSource(commandId, sequence++ and 0xff, CompletableDeferred())
            pendingSource.value = pending
            try {
                checkActive()
                control.session.write(command!!, SourceCodec.encode(commandId, pending.sequence, payload))
                val frame = withTimeoutOrNull(6_000.milliseconds) { pending.reply.await() }
                    ?: throw DropException.Timeout("9ECA command $commandId")
                checkActive()
                frame.payload
            } finally {
                pendingSource.compareAndSet(pending, null)
            }
        }

    override suspend fun readSourceCapability(): ByteArray = control.transaction {
        requireReady(probing = false)
        control.session.read(capability ?: throw DropException.UnsupportedCapability("9ECA capability characteristic"))
            .also { checkActive() }
    }

    override suspend fun readSourceInfo(): ByteArray = readSourceInfo(probing = false)

    private suspend fun readSourceInfo(probing: Boolean): ByteArray = control.transaction {
        requireReady(probing)
        control.session.read(info ?: throw DropException.UnsupportedCapability("9ECA info characteristic"))
            .also { checkActive() }
    }

    private fun requireReady(probing: Boolean) {
        checkActive()
        control.requireReady(probing)
        if (command == null || response == null) throw DropException.UnsupportedCapability("9ECA")
    }

    /** Responses fulfill pending only; unsolicited notifications update only Source snapshots. */
    private fun onSource(bytes: ByteArray) {
        checkActive()
        val frame = SourceCodec.decode(bytes) ?: return
        checkActive()
        if (frame.type == SourceCodec.RESPONSE) {
            val pending = pendingSource.value
            if (pending != null && pending.commandId == frame.commandId && pending.sequence == frame.sequence)
                pending.reply.complete(frame)
        } else {
            control.emit(DropEvent.SourceNotification(frame.commandId, frame.payload))
            try {
                when (frame.commandId) {
                    129, 130 -> mutate { it.copy(sourceStatus = SourceCodec.sourceStatus(frame.payload)) }
                    133 -> mutate { it.copy(volume = SourceCodec.volume(frame.payload)) }
                    134 -> mutate { it.copy(presetEq = SourceCodec.presetEq(frame.payload)) }
                    136 -> mutate { it.copy(micGain = SourceCodec.micGain(frame.payload)) }
                }
            } catch (_: DropException.Protocol) {
                // Malformed unsolicited payloads do not invalidate an unrelated request.
            }
        }
    }
}
