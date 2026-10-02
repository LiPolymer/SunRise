package ink.lipoly.app.sunrise.support

import ink.lipoly.app.sunrise.blueConnector.*
import ink.lipoly.app.sunrise.drop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Test-only radio facts and wire interpreter; callers still explicitly own GATT connections. */
class FakeBtManager(private val scope: CoroutineScope) : BtManager {
    override val availability = MutableStateFlow(BtAvailability.ENABLED)
    override val devices = MutableStateFlow<List<BtDevice>>(emptyList())
    override val connectedAudioDevices = MutableStateFlow<List<BtDevice>>(emptyList())
    override val events = MutableSharedFlow<BtEvent>(extraBufferCapacity = 64)
    var audioCandidates: List<FakeBtDevice> = emptyList()
    var bonded: List<FakeBtDevice> = emptyList()
    var scanned: List<FakeBtDevice> = emptyList()
    var scanFailure: BtException? = null
    var closed = false
        private set
    private val handles = mutableMapOf<String, FakeBtDevice>()
    private var nextSessionId = 0L
    private val audioRefreshGates = ArrayDeque<NativeCallbackGate>()

    override fun device(address: String): FakeBtDevice = handles.getOrPut(address.uppercase()) {
        FakeBtDevice(this, address.uppercase(), scope).also { devices.value = handles.values.toList() + it }
    }
    fun addDevice(address: String, name: String = "Test headset", config: FakeDeviceConfig = FakeDeviceConfig()): FakeBtDevice =
        device(address).also {
            it.info.value = BtDeviceInfo(name, BtDeviceKind.DUAL, BtBondState.BONDED)
            it.config = config
        }
    internal fun sessionId(): Long = ++nextSessionId
    internal fun changed(device: FakeBtDevice, state: GattState) {
        events.tryEmit(BtEvent.OnGattStateChanged(device, state, this))
    }
    suspend fun publishAudioCandidates() {
        connectedAudioDevices.value = audioCandidates
        audioCandidates.forEach { events.emit(BtEvent.OnDiscovered(it, this)) }
    }
    fun holdAudioRefresh(): NativeCallbackGate = NativeCallbackGate().also { audioRefreshGates += it }
    suspend fun publishDeviceInfo(device: FakeBtDevice, info: BtDeviceInfo) {
        device.info.value = info
        events.emit(BtEvent.OnDeviceChanged(device, this))
    }
    override suspend fun refreshConnectedAudioDevices(): List<BtDevice> {
        val gate = if (audioRefreshGates.isEmpty()) null else audioRefreshGates.removeFirst()
        gate?.started?.complete(Unit)
        try {
            gate?.callback?.await()
            return audioCandidates.also { connectedAudioDevices.value = it }
        } finally { gate?.drained?.complete(Unit) }
    }
    override suspend fun bondedDevices(): List<BtDevice> = bonded
    override suspend fun scanLe(name: String?, address: String?, timeoutMillis: Long): List<BtDevice> {
        scanFailure?.let { throw it }
        return scanned.filter { name == null && address == null || it.address.equals(address, true) || it.info.value.name?.equals(name, true) == true }
    }
    override fun close() {
        if (closed) return
        closed = true
        handles.values.forEach { it.gatt.closeManager() }
        availability.value = BtAvailability.CLOSED
    }
}

data class FakeDeviceConfig(
    val gaia: Boolean = true,
    val source: Boolean = true,
    val capabilityCharacteristic: Boolean = true,
    val infoCharacteristic: Boolean = true,
    val battery: EarbudBattery = EarbudBattery(42, 43),
    val gaiaFeatures: Set<Int> = setOf(GaiaIds.BATTERY, GaiaIds.ANC_V2),
    val sourceEntries: List<SourceEntry> = listOf(SourceEntry(7, 3), SourceEntry(11, 1)),
    val initialAnc: Int = 0,
    val ancWriteToRead: Map<Int, Int> = emptyMap(),
)

class FakeBtDevice internal constructor(
    override val manager: FakeBtManager,
    override val address: String,
    scope: CoroutineScope,
) : BtDevice {
    override val info = MutableStateFlow(BtDeviceInfo())
    var config = FakeDeviceConfig()
    override val gatt = FakeBtGatt(this, scope)
    override fun requestBond(): Boolean {
        info.value = info.value.copy(bondState = BtBondState.BONDING)
        return true
    }
}

class FakeBtGatt internal constructor(private val device: FakeBtDevice, private val scope: CoroutineScope) : BtGatt {
    override val state = MutableStateFlow(GattState())
    val sessions = mutableListOf<FakeGattSession>()
    var connectCalls = 0
        private set
    val current: FakeGattSession get() = state.value.session as? FakeGattSession ?: error("No connected session")
    private val lifecycle = Mutex()
    private var connecting: Deferred<Result<FakeGattSession>>? = null
    private var connectGate: NativeCallbackGate? = null
    var connectFailure: BtException? = null
    fun holdConnect(): NativeCallbackGate = NativeCallbackGate().also {
        check(connectGate == null) { "A connection gate is already installed" }
        connectGate = it
    }
    override suspend fun connect(): FakeGattSession {
        val attempt = lifecycle.withLock {
            if (device.manager.closed) throw BtException.Disconnected()
            (state.value.session as? FakeGattSession)?.let { return it }
            connecting?.takeUnless { it.isCompleted } ?: run {
                connectCalls++
                val gate = connectGate.also { connectGate = null }
                publish(GattState(GattPhase.CONNECTING))
                scope.async(start = CoroutineStart.LAZY) {
                    gate?.started?.complete(Unit)
                    try {
                        gate?.callback?.await()
                        lifecycle.withLock connection@ {
                            currentCoroutineContext().ensureActive()
                            connectFailure?.let {
                                publish(GattState(GattPhase.ERROR, error = it))
                                return@connection Result.failure<FakeGattSession>(it)
                            }
                            val session = FakeGattSession(device.manager.sessionId(), device, scope) { ended ->
                                if (state.value.session === ended) publish(GattState())
                            }
                            sessions += session
                            publish(GattState(GattPhase.CONNECTED, session))
                            Result.success(session)
                        }
                    } finally { gate?.drained?.complete(Unit) }
                }.also { connecting = it }
            }
        }
        attempt.start()
        return attempt.await().getOrThrow()
    }
    private fun publish(value: GattState) {
        state.value = value
        device.manager.changed(device, value)
    }
    override suspend fun disconnect() {
        val attempt = lifecycle.withLock {
            connecting.also {
                connecting = null
                it?.cancel()
                state.value.session?.close()
                publish(GattState())
            }
        }
        attempt?.join()
    }
    internal fun closeManager() {
        connecting?.cancel()
        connecting = null
        state.value.session?.close()
        publish(GattState(GattPhase.CLOSED))
    }
}

object FakeGattIds {
    const val GAIA_SERVICE = "00001100-d102-11e1-9b23-00025b00a5a5"
    const val GAIA_COMMAND = "00001101-d102-11e1-9b23-00025b00a5a5"
    const val GAIA_RESPONSE = "00001102-d102-11e1-9b23-00025b00a5a5"
    const val GAIA_DATA = "00001103-d102-11e1-9b23-00025b00a5a5"
    const val SOURCE_SERVICE = "9eca0000-7f3a-4f32-9a38-a91b2c6e0100"
    const val SOURCE_COMMAND = "9eca0001-7f3a-4f32-9a38-a91b2c6e0100"
    const val SOURCE_RESPONSE = "9eca0002-7f3a-4f32-9a38-a91b2c6e0100"
    const val SOURCE_NOTIFICATION = "9eca0003-7f3a-4f32-9a38-a91b2c6e0100"
    const val SOURCE_CAPABILITY = "9eca0004-7f3a-4f32-9a38-a91b2c6e0100"
    const val SOURCE_INFO = "9eca0005-7f3a-4f32-9a38-a91b2c6e0100"
}

class NativeCallbackGate {
    val started = CompletableDeferred<Unit>()
    val callback = CompletableDeferred<Unit>()
    val drained = CompletableDeferred<Unit>()
    fun release() { callback.complete(Unit) }
}

class GaiaReplyGate {
    val request = CompletableDeferred<GaiaPacket>()
}
class SourceReplyGate {
    val request = CompletableDeferred<SourceFrame>()
}

class FakeGattSession internal constructor(
    override val id: Long,
    override val device: FakeBtDevice,
    parentScope: CoroutineScope,
    private val onClosed: (FakeGattSession) -> Unit,
) : GattSession {
    private class Characteristic(
        override val serviceUuid: String,
        override val uuid: String,
        override val properties: Set<GattProperty>,
    ) : GattCharacteristic
    private val config = device.config
    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + lifetime)
    private val queue = GattOperationQueue(scope)
    override val mtu = MutableStateFlow(23)
    override val events = MutableSharedFlow<GattEvent>()
    private val handles = mutableListOf<GattCharacteristic>()
    override val services: List<GattService> = buildList {
        fun handle(service: String, uuid: String, property: GattProperty): GattCharacteristic =
            Characteristic(service, uuid, setOf(property)).also { handles += it }
        if (config.gaia) add(GattService(FakeGattIds.GAIA_SERVICE, listOf(
            handle(FakeGattIds.GAIA_SERVICE, FakeGattIds.GAIA_COMMAND, GattProperty.WRITE),
            handle(FakeGattIds.GAIA_SERVICE, FakeGattIds.GAIA_RESPONSE, GattProperty.NOTIFY),
            handle(FakeGattIds.GAIA_SERVICE, FakeGattIds.GAIA_DATA, GattProperty.NOTIFY),
        )))
        if (config.source) add(GattService(FakeGattIds.SOURCE_SERVICE, buildList {
            add(handle(FakeGattIds.SOURCE_SERVICE, FakeGattIds.SOURCE_COMMAND, GattProperty.WRITE))
            add(handle(FakeGattIds.SOURCE_SERVICE, FakeGattIds.SOURCE_RESPONSE, GattProperty.NOTIFY))
            add(handle(FakeGattIds.SOURCE_SERVICE, FakeGattIds.SOURCE_NOTIFICATION, GattProperty.NOTIFY))
            if (config.capabilityCharacteristic) add(handle(FakeGattIds.SOURCE_SERVICE, FakeGattIds.SOURCE_CAPABILITY, GattProperty.READ))
            if (config.infoCharacteristic) add(handle(FakeGattIds.SOURCE_SERVICE, FakeGattIds.SOURCE_INFO, GattProperty.READ))
        }))
    }
    val gaiaWrites = mutableListOf<GaiaPacket>()
    val sourceWrites = mutableListOf<SourceFrame>()
    val scriptedAncReads = ArrayDeque<Int>()
    var ancReadFailure: BtException? = null
    var rawAnc = config.initialAnc
        private set
    var closed = false
        private set
    private var selectedSource = config.sourceEntries.firstOrNull()?.sourceId ?: 0
    private val nativeGates = mutableListOf<Triple<String, String, NativeCallbackGate>>()
    private val gaiaGates = mutableListOf<Triple<Int, Int, GaiaReplyGate>>()
    private val sourceGates = mutableListOf<Pair<Int, SourceReplyGate>>()
    private val subscriptions = mutableSetOf<String>()

    fun characteristic(uuid: String): GattCharacteristic = handles.single { it.uuid == uuid }
    fun holdNativeWrite(uuid: String = FakeGattIds.GAIA_COMMAND): NativeCallbackGate = holdNative("write", uuid)
    fun holdNativeRead(uuid: String): NativeCallbackGate = holdNative("read", uuid)
    private fun holdNative(operation: String, uuid: String): NativeCallbackGate = NativeCallbackGate().also { nativeGates += Triple(operation, uuid, it) }
    fun holdGaia(feature: Int, command: Int): GaiaReplyGate = GaiaReplyGate().also { gaiaGates += Triple(feature, command, it) }
    fun holdSource(command: Int): SourceReplyGate = SourceReplyGate().also { sourceGates += command to it }
    private fun validate(handle: GattCharacteristic) {
        if (closed) throw BtException.Disconnected()
        if (handles.none { it === handle }) throw BtException.InvalidGattHandle()
    }
    private suspend fun <T> native(operation: String, handle: GattCharacteristic, block: suspend () -> T): T {
        validate(handle)
        return queue.run {
            validate(handle)
            val index = nativeGates.indexOfFirst { it.first == operation && it.second == handle.uuid }
            val gate = if (index >= 0) nativeGates.removeAt(index).third else null
            gate?.started?.complete(Unit)
            try {
                gate?.callback?.await()
                block()
            } finally { gate?.drained?.complete(Unit) }
        }
    }
    override suspend fun read(characteristic: GattCharacteristic): ByteArray = native("read", characteristic) {
        when (characteristic.uuid) {
            FakeGattIds.SOURCE_CAPABILITY -> byteArrayOf(1, config.sourceEntries.size.toByte(), selectedSource.toByte(), 0) + entries()
            FakeGattIds.SOURCE_INFO -> firmware()
            else -> throw BtException.UnsupportedOperation("read ${characteristic.uuid}")
        }
    }
    override suspend fun write(characteristic: GattCharacteristic, value: ByteArray) = native("write", characteristic) {
        when (characteristic.uuid) {
            FakeGattIds.GAIA_COMMAND -> interpretGaia(value)
            FakeGattIds.SOURCE_COMMAND -> interpretSource(value)
            else -> throw BtException.UnsupportedOperation("write ${characteristic.uuid}")
        }
    }
    override suspend fun setNotifications(characteristic: GattCharacteristic, enabled: Boolean) = native("notify", characteristic) {
        if (enabled) subscriptions += characteristic.uuid else subscriptions -= characteristic.uuid
        Unit
    }
    override suspend fun requestMtu(value: Int): Int = queue.run {
        if (closed) throw BtException.Disconnected()
        mtu.value = value
        value
    }
    private fun entries(): ByteArray = config.sourceEntries.flatMap { listOf(it.sourceId.toByte(), it.flags.toByte()) }.toByteArray()
    private fun firmware(): ByteArray = byteArrayOf(1, 0, 4, 0, 2, 3, 4, 0, 9, 0, 0, 0)
    private fun isAncGet(packet: GaiaPacket): Boolean =
        packet.feature in setOf(GaiaIds.ANC_V1, GaiaIds.ANC_V2, GaiaIds.AUDIO_CURATION) &&
            packet.command == if (packet.feature == GaiaIds.ANC_V1) GaiaIds.Anc.V1_GET else GaiaIds.Anc.GET_MODE
    private fun isAncSet(packet: GaiaPacket): Boolean =
        packet.feature in setOf(GaiaIds.ANC_V1, GaiaIds.ANC_V2, GaiaIds.AUDIO_CURATION) &&
            packet.command == if (packet.feature == GaiaIds.ANC_V1) GaiaIds.Anc.V1_SET else GaiaIds.Anc.SET_MODE
    private suspend fun interpretGaia(value: ByteArray) {
        val packet = GaiaCodec.decode(value) ?: error("Invalid GAIA command bytes")
        check(packet.type == GaiaCodec.COMMAND)
        gaiaWrites += packet
        if (isAncSet(packet)) {
            val writeValue = packet.payload.single().toInt() and 255
            rawAnc = config.ancWriteToRead[writeValue] ?: writeValue
            return // Deliberately no SET ACK: success must come from the independent GET.
        }
        val index = gaiaGates.indexOfFirst { it.first == packet.feature && it.second == packet.command }
        if (index >= 0) {
            gaiaGates.removeAt(index).third.request.complete(packet)
            return
        }
        val payload = when {
            packet.feature == GaiaIds.BASIC && packet.command in setOf(GaiaIds.Basic.FEATURES, GaiaIds.Basic.FEATURES_NEXT) ->
                byteArrayOf(0) + config.gaiaFeatures.sorted().flatMap { listOf(it.toByte(), 1.toByte()) }.toByteArray()
            packet.feature == GaiaIds.BATTERY && packet.command == GaiaIds.Battery.LEVELS -> batteryPayload(config.battery)
            isAncGet(packet) -> {
                ancReadFailure?.let { throw it }
                byteArrayOf((if (scriptedAncReads.isEmpty()) rawAnc else scriptedAncReads.removeFirst()).toByte())
            }
            else -> byteArrayOf(0)
        }
        check(FakeGattIds.GAIA_RESPONSE in subscriptions) { "GAIA response notifications were not enabled" }
        replyGaia(packet, payload)
    }
    private suspend fun interpretSource(value: ByteArray) {
        check(value.size >= 6 && value[0] == 0xa5.toByte() && value[1].toInt() == 1 &&
            value[2].toInt() == SourceCodec.COMMAND && value.size == 6 + (value[5].toInt() and 255))
        val frame = SourceFrame(SourceCodec.COMMAND, value[3].toInt() and 255, value[4].toInt() and 255, value.copyOfRange(6, value.size))
        sourceWrites += frame
        val index = sourceGates.indexOfFirst { it.first == frame.commandId }
        if (index >= 0) {
            sourceGates.removeAt(index).second.request.complete(frame)
            return
        }
        val payload = when (frame.commandId) {
            SourceIds.GET_FW_VERSION -> byteArrayOf(0) + firmware()
            SourceIds.GET_CAPABILITY -> byteArrayOf(0, frame.payload.first(), 1, config.sourceEntries.size.toByte()) + entries()
            SourceIds.SET_AUDIO_SOURCE -> {
                val requested = frame.payload.first().toInt() and 255
                check(config.sourceEntries.any { it.sourceId == requested }) { "Source ID must come from capability entries" }
                selectedSource = requested
                byteArrayOf(0, requested.toByte(), requested.toByte(), 0, 0)
            }
            SourceIds.GET_AUDIO_SOURCE -> byteArrayOf(0, selectedSource.toByte(), selectedSource.toByte(), 0, 0)
            SourceIds.PING -> byteArrayOf(0)
            else -> error("Unimplemented test wire command ${frame.commandId}")
        }
        check(FakeGattIds.SOURCE_RESPONSE in subscriptions) { "9ECA response notifications were not enabled" }
        replySource(frame, payload)
    }
    suspend fun replyGaia(request: GaiaPacket, payload: ByteArray, vendor: Int = request.vendor,
                          feature: Int = request.feature, command: Int = request.command) {
        emitGaia(vendor, feature, GaiaCodec.RESPONSE, command, payload)
    }
    suspend fun emitGaia(vendor: Int = 0x001d, feature: Int, type: Int = GaiaCodec.NOTIFICATION,
                         command: Int, payload: ByteArray) {
        val bytes = GaiaCodec.encode(GaiaCommand(feature, command, payload, vendor))
        bytes[2] = (bytes[2].toInt() or (type shl 7 shr 8)).toByte()
        bytes[3] = (bytes[3].toInt() or (type shl 7)).toByte()
        events.emit(GattEvent.ValueChanged(characteristic(FakeGattIds.GAIA_RESPONSE), bytes))
    }
    suspend fun replySource(request: SourceFrame, payload: ByteArray, commandId: Int = request.commandId,
                            sequence: Int = request.sequence) {
        emitSource(commandId, sequence, SourceCodec.RESPONSE, payload)
    }
    suspend fun emitSource(commandId: Int, sequence: Int = 0, type: Int = SourceCodec.NOTIFICATION, payload: ByteArray) {
        val bytes = SourceCodec.encode(commandId, sequence, payload)
        bytes[2] = type.toByte()
        events.emit(GattEvent.ValueChanged(characteristic(FakeGattIds.SOURCE_RESPONSE), bytes))
    }
    fun batteryPayload(battery: EarbudBattery = config.battery): ByteArray =
        byteArrayOf(1, requireNotNull(battery.left).toByte(), 2, requireNotNull(battery.right).toByte())
    override fun close() {
        if (closed) return
        closed = true
        queue.close()
        lifetime.cancel()
        onClosed(this)
    }
}
