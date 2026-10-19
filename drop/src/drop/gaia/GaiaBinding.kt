package ink.lipoly.app.sunrise.drop.gaia

import ink.lipoly.app.sunrise.bluetooth.GattEvent
import ink.lipoly.app.sunrise.bluetooth.RfcommStream
import ink.lipoly.app.sunrise.drop.BatteryReading
import ink.lipoly.app.sunrise.drop.ControlSession
import ink.lipoly.app.sunrise.drop.DropEvent
import ink.lipoly.app.sunrise.drop.DropException
import ink.lipoly.app.sunrise.drop.DropProfile
import ink.lipoly.app.sunrise.drop.DropState
import ink.lipoly.app.sunrise.drop.EarbudBatteryReading
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.time.Duration.Companion.milliseconds

/** GAIA wire state for one shared GATT epoch; closing it never closes the shared session. */
internal class GaiaBinding(
    private val control: ControlSession,
    private val resolvedProfile: DropProfile,
    classicEq: RfcommStream? = null,
) : GaiaControlSession {
    private val closed = MutableStateFlow(false)
    private val pendingGaia = MutableStateFlow<PendingGaia?>(null)
    private var collector: Job? = null
    private var selectedAncPath = AncPath.UNKNOWN
    private val gaiaCommand = control.characteristic(GaiaGattIds.GAIA_SERVICE, GaiaGattIds.GAIA_COMMAND)
    private val gaiaResponse = control.characteristic(GaiaGattIds.GAIA_SERVICE, GaiaGattIds.GAIA_RESPONSE)
    private val gaiaData = control.characteristic(GaiaGattIds.GAIA_SERVICE, GaiaGattIds.GAIA_DATA)

    override val state = control.state
    val controls: GaiaControls = GaiaControlsImpl(this, classicEq)
    var complete = false
        private set
    private val isOpen: Boolean get() = !closed.value && control.isOpen

    private data class PendingGaia(
        val command: GaiaCommand,
        val reply: CompletableDeferred<GaiaPacket>,
        val expectedPeqRange: IntRange? = null,
    )

    /** One object per binding; its owner is only set while holding the shared transaction gate. */
    private val gaiaTransaction = object : GaiaTransaction {
        var owner: Job? = null
        private suspend fun checkOwner() {
            check(owner != null && currentCoroutineContext().job === owner) {
                "GAIA transaction used outside its owning coroutine"
            }
            checkActive()
        }
        override val maxWriteSize: Int get() {
            check(owner != null) { "GAIA transaction has ended" }
            checkActive()
            return control.session.mtu.value - 3
        }
        override suspend fun request(command: GaiaCommand, expectedPeqRange: IntRange?): GaiaPacket {
            checkOwner()
            return requestGaiaLocked(command, expectedPeqRange)
        }
        override suspend fun send(command: GaiaCommand) {
            checkOwner()
            sendGaiaLocked(command)
        }
    }

    init {
        control.onClose(::close)
    }

    fun close() {
        if (!closed.compareAndSet(expect = false, update = true)) return
        pendingGaia.value?.reply?.completeExceptionally(DropException.Disconnected())
        collector?.cancel()
    }

    override fun profile(): DropProfile {
        checkActive()
        return resolvedProfile
    }

    override fun ancPath(): AncPath {
        checkActive()
        return selectedAncPath
    }

    override fun mutate(block: (DropState) -> DropState) {
        checkActive()
        control.mutate { if (isOpen) block(it) else it }
        checkActive()
    }

    private fun checkActive() {
        if (closed.value) throw DropException.Disconnected()
        control.checkActive()
    }

    private fun requireReady(probing: Boolean) {
        checkActive()
        control.requireReady(probing)
        if (gaiaCommand == null || gaiaResponse == null)
            throw DropException.UnsupportedCapability("GAIA")
    }

    /** Subscribe to the raw stream before CCCD can synchronously produce any response. */
    suspend fun subscribe() = control.transaction {
        requireReady(probing = true)
        if (collector != null) return@transaction
        collector = control.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            control.session.events.collect { event ->
                if (event is GattEvent.ValueChanged && isOpen &&
                    (event.characteristic === gaiaResponse || event.characteristic === gaiaData)) {
                    try {
                        onGaia(event.value)
                    } catch (_: DropException.Disconnected) {
                        // Retirement can overtake decoding; old frames cannot publish state.
                    }
                }
            }
        }
        if (!isOpen) {
            collector?.cancel()
            throw DropException.Disconnected()
        }
        control.session.setNotifications(gaiaResponse!!, true)
        gaiaData?.let { control.optionalOperation { control.session.setNotifications(it, true) } }
        checkActive()
    }

    /** Capability completeness is local; the owner combines families and publishes READY. */
    suspend fun initialize() {
        checkActive()
        var features = emptySet<Int>()
        var gaiaComplete = false
        var answer = optionalGaia(GaiaCommand(GaiaIds.BASIC, GaiaIds.Basic.FEATURES))
        if (answer != null) {
            var remainingPages = 8
            while (remainingPages-- > 0) {
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
        mutate {
            it.copy(capabilities = it.capabilities.copy(
                gaiaFeatures = features,
                ancModes = GaiaProfileMappings.supportedModes(selectedAncPath, resolvedProfile),
            ))
        }
        checkActive()
        complete = gaiaComplete
    }

    override suspend fun requestGaia(command: GaiaCommand): GaiaPacket = requestGaia(command, probing = false)

    private suspend fun requestGaia(command: GaiaCommand, probing: Boolean): GaiaPacket = control.transaction {
        requireReady(probing)
        requestGaiaLocked(command)
    }

    /** Install pending before writing; clear by identity on response, failure or cancellation. */
    private suspend fun requestGaiaLocked(
        command: GaiaCommand,
        expectedPeqRange: IntRange? = null,
    ): GaiaPacket {
        require(expectedPeqRange == null ||
            (command.feature == GaiaIds.MUSIC_PROCESSING && command.command == GaiaIds.Eq.GET_USER_CONFIG))
        val pending = PendingGaia(command, CompletableDeferred(), expectedPeqRange)
        pendingGaia.value = pending
        return try {
            sendGaiaLocked(command)
            val answer = withTimeoutOrNull(6_000.milliseconds) { pending.reply.await() }
                ?: throw DropException.Timeout("GAIA ${command.feature}/${command.command}")
            checkActive()
            answer
        } finally {
            pendingGaia.compareAndSet(pending, null)
        }
    }

    private suspend fun sendGaiaLocked(command: GaiaCommand) {
        checkActive()
        control.session.write(gaiaCommand!!, GaiaCodec.encode(command))
        checkActive()
    }

    override suspend fun <T> withGaiaTransaction(block: suspend GaiaTransaction.() -> T): T = control.transaction {
        requireReady(probing = false)
        gaiaTransaction.owner = currentCoroutineContext().job
        try {
            block(gaiaTransaction).also { checkActive() }
        } finally {
            gaiaTransaction.owner = null
        }
    }

    private suspend fun optionalGaia(command: GaiaCommand): GaiaPacket? = try {
        requestGaia(command, probing = true)
    } catch (error: CancellationException) {
        throw error
    } catch (error: DropException) {
        if (!isOpen || error is DropException.Disconnected) throw error
        null
    }

    /** SET ANC, shutdown and raw send complete only the native write, without a response slot. */
    override suspend fun sendGaia(command: GaiaCommand) = control.transaction {
        requireReady(probing = false)
        sendGaiaLocked(command)
    }

    /** Match responses exactly and merge battery pairs into the shared epoch snapshot. */
    private fun onGaia(bytes: ByteArray) {
        if (!isOpen) return
        val packet = GaiaCodec.decode(bytes) ?: return
        if (packet.type == GaiaCodec.RESPONSE) {
            val pending = pendingGaia.value
            if (pending != null && packet.vendor == pending.command.vendor &&
                packet.feature == pending.command.feature && packet.command == pending.command.command) {
                val range = pending.expectedPeqRange
                // Short matching responses must reach strict parsing, not become opaque timeouts.
                if (range == null || packet.payload.size < 2 ||
                    ((packet.payload[0].toInt() and 0xff) == range.first &&
                        (packet.payload[1].toInt() and 0xff) == range.last)) {
                    checkActive()
                    pending.reply.complete(packet)
                }
            }
        } else if (packet.type == GaiaCodec.NOTIFICATION) {
            checkActive()
            control.emit(DropEvent.GaiaNotification(packet))
        }
        if (packet.feature == GaiaIds.BATTERY && packet.command == GaiaIds.Battery.LEVELS) {
            mutate { current ->
                var left = current.battery.left
                var right = current.battery.right
                var case = current.battery.case
                for (index in 0 until packet.payload.size - 1 step 2) {
                    val value = packet.payload[index + 1].toInt() and 0xff
                    when (packet.payload[index].toInt() and 0xff) {
                        1 -> left = BatteryReading(value, value)
                        2 -> right = BatteryReading(value, value)
                        3 -> case = BatteryReading(value, value)
                    }
                }
                current.copy(battery = EarbudBatteryReading(left, right, case))
            }
        }
    }

    override fun normalizeTransport(error: Exception): DropException = control.normalizeTransport(error)

    companion object {
        private val ANC_PROBES = listOf(
            GaiaCommand(GaiaIds.AUDIO_CURATION, GaiaIds.AudioCuration.GET_MODE) to AncPath.AUDIO_CURATION,
            GaiaCommand(GaiaIds.ANC_V2, GaiaIds.Anc.GET_MODE) to AncPath.V2,
            GaiaCommand(GaiaIds.ANC_V1, GaiaIds.Anc.V1_GET) to AncPath.V1,
        )
    }
}
