package ink.lipoly.app.sunrise.drop

import ink.lipoly.app.sunrise.blueConnector.BtException
import ink.lipoly.app.sunrise.blueConnector.GattCharacteristic
import ink.lipoly.app.sunrise.blueConnector.GattEvent
import ink.lipoly.app.sunrise.blueConnector.GattProperty
import ink.lipoly.app.sunrise.blueConnector.GattSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** All protocol state, controls and pending replies belong to exactly one GATT epoch. */
internal class DropControlBinding(
    val session: GattSession,
    parentScope: CoroutineScope,
    private val resolvedProfile: DropProfile,
    private val isCurrent: (DropControlBinding) -> Boolean,
    private val publishState: (DropControlBinding) -> Unit,
    private val publishEvent: (DropControlBinding, DropEvent) -> Unit,
) : DropControlSession {
    // The lifetime sentinel has no children: external calls are cancelled immediately,
    // even while a binding-owned initialization coroutine is still unwinding.
    private val lifetime = Job(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(SupervisorJob(parentScope.coroutineContext[Job]) + Dispatchers.Default)
    private val closed = MutableStateFlow(false)
    private val started = MutableStateFlow(false)
    private val transactions = Mutex()
    private val pendingGaia = MutableStateFlow<PendingGaia?>(null)
    private val pendingSource = MutableStateFlow<PendingSource?>(null)
    private var sequence = 0
    private var selectedAncPath = AncPath.UNKNOWN

    private val gaiaCommand = characteristic(DropGattIds.GAIA_SERVICE, DropGattIds.GAIA_COMMAND)
    private val gaiaResponse = characteristic(DropGattIds.GAIA_SERVICE, DropGattIds.GAIA_RESPONSE)
    private val gaiaData = characteristic(DropGattIds.GAIA_SERVICE, DropGattIds.GAIA_DATA)
    private val sourceCommand = characteristic(DropGattIds.SOURCE_SERVICE, DropGattIds.SOURCE_COMMAND)
    private val sourceResponse = characteristic(DropGattIds.SOURCE_SERVICE, DropGattIds.SOURCE_RESPONSE)
    private val sourceNotification = characteristic(DropGattIds.SOURCE_SERVICE, DropGattIds.SOURCE_NOTIFICATION)
    private val sourceCapability = characteristic(DropGattIds.SOURCE_SERVICE, DropGattIds.SOURCE_CAPABILITY)
        ?.takeIf { GattProperty.READ in it.properties }
    private val sourceInfo = characteristic(DropGattIds.SOURCE_SERVICE, DropGattIds.SOURCE_INFO)
        ?.takeIf { GattProperty.READ in it.properties }
    private val protocols = buildSet {
        if (gaiaCommand != null && gaiaResponse != null) add(DropProtocol.GAIA_BLE)
        if (sourceCommand != null && sourceResponse != null) add(DropProtocol.SOURCE_9ECA)
    }
    private val mutableState = MutableStateFlow(DropState(phase = DropPhase.PROBING, protocols = protocols))
    override val state = mutableState.asStateFlow()
    val ready = CompletableDeferred<Unit>()
    val gaia: GaiaControls = GaiaControlsImpl(this)
    val source: SourceControls = SourceControlsImpl(this)
    val isOpen: Boolean get() = !closed.value && lifetime.isActive

    private data class PendingGaia(val command: GaiaCommand, val reply: CompletableDeferred<GaiaPacket>)
    private data class PendingSource(val commandId: Int, val sequence: Int, val reply: CompletableDeferred<SourceFrame>)
    private class BindingDisconnectedCancellation : CancellationException("Drop binding disconnected")

    init {
        lifetime.invokeOnCompletion { close() }
    }

    fun startInitialization() {
        if (!started.compareAndSet(false, true)) return
        if (!isOpen) {
            ready.completeExceptionally(DropException.Disconnected())
            return
        }
        scope.launch {
            try {
                initialize()
                ensureBound()
                ready.complete(Unit)
            } catch (e: CancellationException) {
                ready.completeExceptionally(DropException.Disconnected())
                close()
                throw e
            } catch (e: Exception) {
                val failure = normalize(e)
                if (isOpen && isCurrent(this@DropControlBinding)) {
                    mutableState.update {
                        if (isOpen && isCurrent(this@DropControlBinding))
                            it.copy(phase = DropPhase.ERROR, error = failure)
                        else it
                    }
                    publishState(this@DropControlBinding)
                    publishEvent(this@DropControlBinding, DropEvent.Error(failure))
                }
                ready.completeExceptionally(failure)
                close()
            }
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        val failure = DropException.Disconnected()
        ready.completeExceptionally(failure)
        pendingGaia.value?.reply?.completeExceptionally(failure)
        pendingSource.value?.reply?.completeExceptionally(failure)
        lifetime.cancel(BindingDisconnectedCancellation())
        scope.cancel(BindingDisconnectedCancellation())
        // Notifications and native GATT operations are owned by the session, not us.
    }

    override fun profile(): DropProfile {
        ensureBound()
        return resolvedProfile
    }

    override fun ancPath(): AncPath {
        ensureBound()
        return selectedAncPath
    }

    override fun mutate(block: (DropState) -> DropState) {
        ensureBound()
        mutableState.update { current -> if (isOpen && isCurrent(this)) block(current) else current }
        publishState(this)
        ensureBound()
    }

    private fun characteristic(service: String, uuid: String): GattCharacteristic? =
        session.services.firstOrNull { it.uuid == service }?.characteristics?.firstOrNull { it.uuid == uuid }

    private fun ensureBound() {
        if (!isOpen || !isCurrent(this)) throw DropException.Disconnected()
    }

    /** Binding loss cancels our child only; caller cancellation remains its own cancellation. */
    private suspend fun <T> inBinding(block: suspend () -> T): T = try {
        coroutineScope {
            val call = currentCoroutineContext().job
            val listener = lifetime.invokeOnCompletion { call.cancel(BindingDisconnectedCancellation()) }
            try {
                ensureBound()
                block().also { ensureBound() }
            } finally {
                listener.dispose()
            }
        }
    } catch (e: BindingDisconnectedCancellation) {
        currentCoroutineContext().ensureActive()
        throw DropException.Disconnected()
    } catch (e: BtException) {
        throw normalize(e)
    }

    private suspend fun initialize() = inBinding {
        if (protocols.isEmpty()) throw DropException.UnsupportedDevice()
        // UNDISPATCHED reaches SharedFlow subscription before any CCCD/write can reply.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            session.events.collect { event ->
                if (event is GattEvent.ValueChanged && isOpen && isCurrent(this@DropControlBinding)) {
                    try {
                        when {
                            event.characteristic === gaiaResponse || event.characteristic === gaiaData ->
                                onGaia(event.value)
                            event.characteristic === sourceResponse || event.characteristic === sourceNotification ->
                                onSource(event.value)
                        }
                    } catch (_: DropException.Disconnected) {
                        // A disconnect can overtake decoding; the retired frame is ignored.
                    }
                }
            }
        }
        if (DropProtocol.GAIA_BLE in protocols) {
            session.setNotifications(gaiaResponse!!, true)
            gaiaData?.let { optionalOperation { session.setNotifications(it, true) } }
        }
        if (DropProtocol.SOURCE_9ECA in protocols) {
            session.setNotifications(sourceResponse!!, true)
            sourceNotification?.let { optionalOperation { session.setNotifications(it, true) } }
        }
        optionalOperation { session.requestMtu(247) }
        probe()
    }

    private suspend fun optionalOperation(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Rejection on a live session is best-effort; native timeout/loss is not.
            if (!isOpen || !isCurrent(this)) throw normalize(e)
        }
        ensureBound()
    }

    private suspend fun probe() {
        var features = emptySet<Int>()
        var gaiaComplete = DropProtocol.GAIA_BLE !in protocols
        if (DropProtocol.GAIA_BLE in protocols) {
            var answer = optionalGaia(GaiaCommand(GaiaIds.BASIC, GaiaIds.Basic.FEATURES))
            if (answer != null) {
                for (page in 0..7) {
                    val payload = answer?.payload ?: break
                    features = features + GaiaCodec.features(payload)
                    val pairList = payload.size >= 3 && payload.size % 2 == 1 &&
                        (payload[0].toInt() and 0xff) <= 1
                    if (!pairList) {
                        gaiaComplete = payload.size % 4 == 0
                        break
                    }
                    if (payload[0].toInt() == 0) {
                        gaiaComplete = true
                        break
                    }
                    answer = optionalGaia(GaiaCommand(GaiaIds.BASIC, GaiaIds.Basic.FEATURES_NEXT))
                }
            }
            selectedAncPath = when {
                GaiaIds.AUDIO_CURATION in features -> AncPath.AUDIO_CURATION
                GaiaIds.ANC_V2 in features -> AncPath.V2
                GaiaIds.ANC_V1 in features -> AncPath.V1
                else -> AncPath.UNKNOWN
            }
            if (selectedAncPath == AncPath.UNKNOWN) {
                for ((command, path) in ANC_PROBES) {
                    if (optionalGaia(command) != null) {
                        selectedAncPath = path
                        features = features + command.feature
                        break
                    }
                }
            }
        }
        var sourceFeatures = emptySet<SourceFeature>()
        var sourceComplete = DropProtocol.SOURCE_9ECA !in protocols
        if (DropProtocol.SOURCE_9ECA in protocols) {
            val firmware = try {
                SourceCodec.firmware(readSourceInfo(probing = true))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isOpen || !isCurrent(this)) throw normalize(e)
                try {
                    val response = requestSource(SourceIds.GET_FW_VERSION, byteArrayOf(), probing = true)
                    if (SourceCodec.status(response) == 0) SourceCodec.firmware(response, 1) else null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!isOpen || !isCurrent(this)) throw normalize(e)
                    null
                }
            }
            if (firmware != null) {
                sourceFeatures = firmware.features
                sourceComplete = true
            }
        }
        mutate {
            it.copy(
                phase = DropPhase.READY,
                capabilities = DropCapabilities(
                    features, DropProfiles.supportedModes(selectedAncPath, resolvedProfile),
                    sourceFeatures, gaiaComplete && sourceComplete,
                ),
                error = null,
            )
        }
    }

    override suspend fun requestGaia(command: GaiaCommand): GaiaPacket = requestGaia(command, probing = false)

    private suspend fun requestGaia(command: GaiaCommand, probing: Boolean): GaiaPacket = inBinding {
        transactions.withLock {
            requireProtocol(DropProtocol.GAIA_BLE, probing)
            val pending = PendingGaia(command, CompletableDeferred())
            pendingGaia.value = pending
            try {
                ensureBound()
                session.write(gaiaCommand!!, GaiaCodec.encode(command))
                withTimeoutOrNull(6_000) { pending.reply.await() }
                    ?: throw DropException.Timeout("GAIA ${command.feature}/${command.command}")
            } finally {
                pendingGaia.compareAndSet(pending, null)
            }
        }
    }

    private suspend fun optionalGaia(command: GaiaCommand): GaiaPacket? = try {
        requestGaia(command, probing = true)
    } catch (e: CancellationException) {
        throw e
    } catch (e: DropException) {
        if (!isOpen || !isCurrent(this) || e is DropException.Disconnected) throw e
        null
    }

    override suspend fun sendGaia(command: GaiaCommand) = inBinding {
        transactions.withLock {
            requireProtocol(DropProtocol.GAIA_BLE, probing = false)
            session.write(gaiaCommand!!, GaiaCodec.encode(command))
        }
    }

    override suspend fun requestSource(commandId: Int, payload: ByteArray): ByteArray =
        requestSource(commandId, payload, probing = false)

    private suspend fun requestSource(commandId: Int, payload: ByteArray, probing: Boolean): ByteArray = inBinding {
        transactions.withLock {
            requireProtocol(DropProtocol.SOURCE_9ECA, probing)
            val pending = PendingSource(commandId, sequence++ and 0xff, CompletableDeferred())
            pendingSource.value = pending
            try {
                ensureBound()
                session.write(sourceCommand!!, SourceCodec.encode(commandId, pending.sequence, payload))
                (withTimeoutOrNull(6_000) { pending.reply.await() }
                    ?: throw DropException.Timeout("9ECA command $commandId")).payload
            } finally {
                pendingSource.compareAndSet(pending, null)
            }
        }
    }

    override suspend fun readSourceCapability(): ByteArray = inBinding {
        requireProtocol(DropProtocol.SOURCE_9ECA, probing = false)
        session.read(sourceCapability ?: throw DropException.UnsupportedCapability("9ECA capability characteristic"))
    }

    override suspend fun readSourceInfo(): ByteArray = readSourceInfo(probing = false)

    private suspend fun readSourceInfo(probing: Boolean): ByteArray = inBinding {
        requireProtocol(DropProtocol.SOURCE_9ECA, probing)
        session.read(sourceInfo ?: throw DropException.UnsupportedCapability("9ECA info characteristic"))
    }

    private fun requireProtocol(protocol: DropProtocol, probing: Boolean) {
        ensureBound()
        if (state.value.phase != DropPhase.READY && !(probing && state.value.phase == DropPhase.PROBING))
            throw DropException.NotReady()
        if (protocol !in protocols)
            throw DropException.UnsupportedCapability(if (protocol == DropProtocol.GAIA_BLE) "GAIA" else "9ECA")
    }

    private fun onGaia(bytes: ByteArray) {
        if (!isOpen || !isCurrent(this)) return
        val packet = GaiaCodec.decode(bytes) ?: return
        if (packet.type == GaiaCodec.RESPONSE) {
            val pending = pendingGaia.value
            if (pending != null && packet.vendor == pending.command.vendor &&
                packet.feature == pending.command.feature && packet.command == pending.command.command) {
                pending.reply.complete(packet)
            }
        } else if (packet.type == GaiaCodec.NOTIFICATION) {
            publishEvent(this, DropEvent.GaiaNotification(packet))
        }
        if (packet.feature == GaiaIds.BATTERY && packet.command == GaiaIds.Battery.LEVELS) {
            mutate { current ->
                var left = current.battery.left
                var right = current.battery.right
                var case = current.battery.case
                for (index in 0 until packet.payload.size - 1 step 2) {
                    val value = packet.payload[index + 1].toInt() and 0xff
                    when (packet.payload[index].toInt() and 0xff) {
                        1 -> left = value
                        2 -> right = value
                        3 -> case = value
                    }
                }
                current.copy(battery = EarbudBattery(left, right, case))
            }
        }
    }

    private fun onSource(bytes: ByteArray) {
        if (!isOpen || !isCurrent(this)) return
        val frame = SourceCodec.decode(bytes) ?: return
        if (frame.type == SourceCodec.RESPONSE) {
            val pending = pendingSource.value
            if (pending != null && pending.commandId == frame.commandId && pending.sequence == frame.sequence)
                pending.reply.complete(frame)
        } else {
            publishEvent(this, DropEvent.SourceNotification(frame.commandId, frame.payload))
            try {
                when (frame.commandId) {
                    129, 130 -> mutate { it.copy(sourceStatus = SourceCodec.sourceStatus(frame.payload)) }
                    133 -> mutate { it.copy(volume = SourceCodec.volume(frame.payload)) }
                    134 -> mutate { it.copy(presetEq = SourceCodec.presetEq(frame.payload)) }
                    136 -> mutate { it.copy(micGain = SourceCodec.micGain(frame.payload)) }
                }
            } catch (_: DropException.Protocol) {
                // A malformed unsolicited value does not invalidate an outstanding request.
            }
        }
    }

    private fun normalize(error: Exception): DropException = when (error) {
        is DropException -> error
        is BtException.Timeout -> DropException.Timeout(error.operation)
        is BtException.Disconnected -> DropException.Disconnected()
        is BtException.UnsupportedOperation -> DropException.UnsupportedCapability(error.operation)
        is BtException.InvalidGattHandle -> DropException.UnsupportedCapability("GATT characteristic")
        is BtException.PacketTooLarge -> DropException.Protocol(error.message ?: "Packet exceeds negotiated GATT MTU")
        else -> DropException.Transport(error.message ?: "Drop GATT operation failed", error)
    }

    companion object {
        private val ANC_PROBES = listOf(
            GaiaCommand(GaiaIds.AUDIO_CURATION, GaiaIds.AudioCuration.GET_MODE) to AncPath.AUDIO_CURATION,
            GaiaCommand(GaiaIds.ANC_V2, GaiaIds.Anc.GET_MODE) to AncPath.V2,
            GaiaCommand(GaiaIds.ANC_V1, GaiaIds.Anc.V1_GET) to AncPath.V1,
        )
    }
}
