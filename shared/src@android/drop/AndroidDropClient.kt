package ink.lipoly.app.sunrise.drop

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

internal class AndroidDropClient(context: Context, private val options: DropOptions) : DropClient, DropControlSession {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val discovery = AndroidDiscovery(this.context)
    private val preferences = this.context.getSharedPreferences("drop_verified_devices", Context.MODE_PRIVATE)
    private val lifecycle = Mutex()
    private val transactions = Mutex()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val mutableState = MutableStateFlow(DropState())
    private val mutableEvents = MutableSharedFlow<DropEvent>(extraBufferCapacity = 32)
    override val state = mutableState.asStateFlow()
    override val events = mutableEvents.asSharedFlow()
    override val gaia: GaiaControls = GaiaControlsImpl(this)
    override val source: SourceControls = SourceControlsImpl(this)

    private data class PendingGaia(val generation: Long, val command: GaiaCommand,
                                   val reply: CompletableDeferred<GaiaPacket>)
    private data class PendingSource(val generation: Long, val commandId: Int, val sequence: Int,
                                     val reply: CompletableDeferred<SourceFrame>)

    @Volatile private var pendingGaia: PendingGaia? = null
    @Volatile private var pendingSource: PendingSource? = null
    @Volatile private var gatt: AndroidGattLink? = null
    @Volatile private var rfcomm: AndroidRfcommLink? = null
    @Volatile private var lost: CompletableDeferred<Unit>? = null
    private val generation = AtomicLong()
    @Volatile private var closed = false
    @Volatile private var auto = false
    @Volatile private var target: DropDevice? = null
    @Volatile private var ancPath = AncPath.UNKNOWN
    private var connectionJob: Job? = null
    private var pollingJob: Job? = null
    private var sequence = 0

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) !=
                        BluetoothAdapter.STATE_ON) lost?.complete(Unit)
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    @Suppress("DEPRECATION")
                    val device = if (Build.VERSION.SDK_INT >= 33)
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    else intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    if (device?.address?.equals(target?.address, true) == true) lost?.complete(Unit)
                }
            }
            wake.trySend(Unit)
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        context.applicationContext.registerReceiver(receiver, filter)
    }

    override suspend fun discoverConnectedDevices(): List<DropDevice> = discovery.connectedAudioDevices().map {
        DropDevice(it.address, runCatching { it.name }.getOrNull(), preferences.contains(it.address.uppercase()))
    }

    override fun startAutoConnect() {
        ensureOpen()
        auto = true
        val previous = connectionJob
        connectionJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            previous?.cancel()
            closeLinks()
            previous?.join()
            autoLoop()
        }
    }

    override suspend fun connect(address: String) {
        ensureOpen()
        if (!BluetoothAdapter.checkBluetoothAddress(address)) throw DropException.InvalidDevice("Invalid Bluetooth address")
        val adapter = discovery.requireBluetooth()
        val device = adapter.getRemoteDevice(address)
        val selected = DropDevice(device.address, runCatching { device.name }.getOrNull(),
            preferences.contains(device.address.uppercase()))
        val first = CompletableDeferred<Unit>()
        lifecycle.withLock {
            auto = false
            connectionJob?.cancel()
            closeLinks()
            connectionJob?.join()
            target = selected
            connectionJob = scope.launch { connectionLoop(selected, first, retryFirst = false) }
            connectionJob?.invokeOnCompletion {
                if (!first.isCompleted) first.completeExceptionally(DropException.Disconnected())
            }
        }
        first.await()
    }

    override suspend fun disconnect() {
        lifecycle.withLock {
            auto = false
            target = null
            connectionJob?.cancel()
            closeLinks()
            connectionJob?.join()
            connectionJob = null
            mutableState.value = DropState()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        auto = false
        target = null
        connectionJob?.cancel()
        pollingJob?.cancel()
        closeLinks()
        runCatching { context.unregisterReceiver(receiver) }
        wake.close()
        scope.cancel()
        mutableState.value = DropState()
    }

    private suspend fun autoLoop() {
        while (currentCoroutineContext().isActive && auto) {
            try {
                mutableState.update { it.copy(phase = DropPhase.DISCOVERING, error = null) }
                val candidates = discoverConnectedDevices()
                when (candidates.size) {
                    0 -> mutableState.update { it.copy(phase = DropPhase.IDLE, device = null) }
                    1 -> {
                        target = candidates.single()
                        connectionLoop(candidates.single(), null, retryFirst = true)
                        target = null
                    }
                    else -> mutableState.update { it.copy(phase = DropPhase.SELECTION_REQUIRED, device = null) }
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { report(e) }
            awaitWake(5_000)
        }
    }

    private suspend fun connectionLoop(selected: DropDevice, first: CompletableDeferred<Unit>?, retryFirst: Boolean) {
        var everReady = false
        try {
            while (currentCoroutineContext().isActive && (target?.address == selected.address)) {
                if (auto && discoverConnectedDevices().none { it.address.equals(selected.address, true) }) break
                mutableState.value = DropState(
                    phase = if (everReady) DropPhase.RECONNECTING else DropPhase.CONNECTING,
                    device = selected,
                )
                try {
                    openSelected(selected)
                    everReady = true
                    if (!first.isCompletedSafe()) first?.complete(Unit)
                    pollingJob?.cancel()
                    pollingJob = scope.launch {
                        launch {
                            if (state.value.capabilities.ancModes.isNotEmpty()) {
                                try {
                                    gaia.getAncMode()
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    // An unavailable read leaves the mode unknown; the UI can retry.
                                }
                            }
                        }
                        pollBattery()
                    }
                    lost?.await()
                    closeLinks()
                    mutableState.update { it.copy(phase = DropPhase.RECONNECTING) }
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) {
                    closeLinks()
                    report(e)
                    if (!everReady && !retryFirst) {
                        first?.completeExceptionally(normalize(e))
                        target = null
                        break
                    }
                }
                if (currentCoroutineContext().isActive) awaitWake(4_000)
            }
        } finally {
            if (!first.isCompletedSafe()) first?.completeExceptionally(DropException.Disconnected())
            closeLinks()
        }
    }

    private suspend fun openSelected(selected: DropDevice) {
        val adapter = discovery.requireBluetooth()
        val classic = adapter.getRemoteDevice(selected.address)
        val candidates = LinkedHashMap<String, BluetoothDevice>()
        val cached = preferences.getString(selected.address.uppercase(), null)
        if (cached != null && BluetoothAdapter.checkBluetoothAddress(cached)) {
            candidates[cached.uppercase()] = adapter.getRemoteDevice(cached)
        }
        candidates[selected.address.uppercase()] = classic
        discovery.bondedLeWithName(selected.name).forEach { candidates[it.address.uppercase()] = it }
        var lastFailure: DropException? = null
        for (candidate in candidates.values) {
            try {
                if (tryGatt(candidate, selected)) return
            } catch (e: CancellationException) { throw e
            } catch (e: DropException) { lastFailure = e }
        }
        val scanResults = try {
            discovery.scanLe(selected.name, selected.address)
        } catch (e: DropException.MissingPermission) {
            // Direct GATT and RFCOMM remain usable without a scan grant.
            emptyList()
        }
        for (candidate in scanResults) {
            if (candidate.address.uppercase() in candidates) continue
            try {
                if (tryGatt(candidate, selected)) return
            } catch (e: CancellationException) { throw e
            } catch (e: DropException) { lastFailure = e }
        }
        try {
            tryRfcomm(classic, selected)
        } catch (e: DropException) {
            if (lastFailure != null) e.addSuppressed(lastFailure)
            throw e
        }
    }

    private suspend fun tryGatt(device: BluetoothDevice, selected: DropDevice): Boolean {
        val epoch = generation.incrementAndGet()
        val link = AndroidGattLink(context, device,
            onGaia = { onGaia(epoch, it) }, onSource = { onSource(epoch, it) }, onLost = { onLost(epoch) })
        gatt = link
        lost = CompletableDeferred()
        return try {
            val protocols = link.open()
            preferences.edit().putString(selected.address.uppercase(), device.address).apply()
            probe(selected.copy(verified = true), protocols)
            true
        } catch (e: Throwable) {
            if (gatt === link) gatt = null
            link.close()
            if (e is CancellationException) throw e
            if (e is DropException.UnsupportedDevice) false else throw e
        }
    }

    private suspend fun tryRfcomm(device: BluetoothDevice, selected: DropDevice) {
        val epoch = generation.incrementAndGet()
        val link = AndroidRfcommLink(device, scope, onGaia = { onGaia(epoch, it) }, onLost = { onLost(epoch) })
        rfcomm = link
        lost = CompletableDeferred()
        try {
            link.open()
            probe(selected.copy(verified = true), setOf(DropProtocol.GAIA_RFCOMM))
            preferences.edit().putString(selected.address.uppercase(), selected.address).apply()
        } catch (e: Throwable) {
            if (rfcomm === link) rfcomm = null
            link.close()
            throw e
        }
    }

    private suspend fun probe(device: DropDevice, protocols: Set<DropProtocol>) {
        mutableState.value = DropState(phase = DropPhase.PROBING, device = device, protocols = protocols)
        var features = emptySet<Int>()
        var gaiaComplete = protocols.none { it == DropProtocol.GAIA_BLE || it == DropProtocol.GAIA_RFCOMM }
        if (protocols.any { it == DropProtocol.GAIA_BLE || it == DropProtocol.GAIA_RFCOMM }) {
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
            ancPath = when {
                GaiaIds.AUDIO_CURATION in features -> AncPath.AUDIO_CURATION
                GaiaIds.ANC_V2 in features -> AncPath.V2
                GaiaIds.ANC_V1 in features -> AncPath.V1
                else -> AncPath.UNKNOWN
            }
            if (ancPath == AncPath.UNKNOWN) {
                for ((feature, path, command) in listOf(
                    Triple(GaiaIds.AUDIO_CURATION, AncPath.AUDIO_CURATION, GaiaIds.AudioCuration.GET_MODE),
                    Triple(GaiaIds.ANC_V2, AncPath.V2, GaiaIds.Anc.GET_MODE),
                    Triple(GaiaIds.ANC_V1, AncPath.V1, GaiaIds.Anc.V1_GET),
                )) {
                    if (optionalGaia(GaiaCommand(feature, command)) != null) {
                        ancPath = path
                        features = features + feature
                        break
                    }
                }
            }
        } else ancPath = AncPath.UNKNOWN
        var sourceFeatures = emptySet<SourceFeature>()
        var sourceComplete = DropProtocol.SOURCE_9ECA !in protocols
        if (DropProtocol.SOURCE_9ECA in protocols) {
            val firmware = try {
                SourceCodec.firmware(gatt!!.readInfo())
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) {
                try {
                    val response = requestSource(SourceIds.GET_FW_VERSION, probing = true)
                    if (SourceCodec.status(response) == 0) SourceCodec.firmware(response, 1) else null
                } catch (e: CancellationException) { throw e
                } catch (_: Exception) { null }
            }
            if (firmware != null) {
                sourceFeatures = firmware.features
                sourceComplete = true
            }
        }
        if (lost?.isCompleted == true) throw DropException.Disconnected()
        mutableState.update {
            it.copy(phase = DropPhase.READY, capabilities = DropCapabilities(features,
                DropProfiles.supportedModes(ancPath, DropProfiles.resolve(options, device)), sourceFeatures,
                gaiaComplete && sourceComplete), error = null)
        }
    }

    private suspend fun pollBattery() {
        while (currentCoroutineContext().isActive && state.value.phase == DropPhase.READY) {
            if (state.value.protocols.any { it == DropProtocol.GAIA_BLE || it == DropProtocol.GAIA_RFCOMM }) {
                runCatching { gaia.getBattery() }
            }
            delay(30_000)
        }
    }

    override fun profile(): DropProfile = DropProfiles.resolve(options, state.value.device)
    override fun ancPath(): AncPath = ancPath
    override fun mutate(block: (DropState) -> DropState) { mutableState.update(block) }

    override suspend fun requestGaia(command: GaiaCommand): GaiaPacket = requestGaia(command, probing = false)

    private suspend fun requestGaia(command: GaiaCommand, probing: Boolean): GaiaPacket = transactions.withLock {
        requireGaia(probing)
        val epoch = generation.get()
        val pending = PendingGaia(epoch, command, CompletableDeferred())
        pendingGaia = pending
        try {
            writeGaia(GaiaCodec.encode(command))
            withTimeout(6_000) { pending.reply.await() }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw DropException.Timeout("GAIA ${command.feature}/${command.command}")
        } finally { if (pendingGaia === pending) pendingGaia = null }
    }

    private suspend fun optionalGaia(command: GaiaCommand): GaiaPacket? = try {
        requestGaia(command, true)
    } catch (e: CancellationException) { throw e
    } catch (_: DropException) { null }

    override suspend fun sendGaia(command: GaiaCommand) = transactions.withLock {
        requireGaia(false)
        writeGaia(GaiaCodec.encode(command))
    }

    private suspend fun writeGaia(bytes: ByteArray) {
        val ble = gatt
        if (ble?.hasGaia == true) ble.writeGaia(bytes)
        else rfcomm?.write(bytes) ?: throw DropException.Disconnected()
    }

    override suspend fun requestSource(commandId: Int, payload: ByteArray): ByteArray =
        requestSource(commandId, payload, probing = false)

    private suspend fun requestSource(commandId: Int, payload: ByteArray = byteArrayOf(),
                                      probing: Boolean): ByteArray = transactions.withLock {
        requireSource(probing)
        val epoch = generation.get()
        val seq = (sequence++ and 0xff)
        val pending = PendingSource(epoch, commandId, seq, CompletableDeferred())
        pendingSource = pending
        try {
            gatt!!.writeSource(SourceCodec.encode(commandId, seq, payload))
            withTimeout(6_000) { pending.reply.await() }.payload
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw DropException.Timeout("9ECA command $commandId")
        } finally { if (pendingSource === pending) pendingSource = null }
    }

    override suspend fun readSourceCapability(): ByteArray {
        requireSource()
        return gatt!!.readCapability()
    }

    override suspend fun readSourceInfo(): ByteArray {
        requireSource()
        return gatt!!.readInfo()
    }

    private fun requireGaia(probing: Boolean) {
        if (state.value.phase != DropPhase.READY && !(probing && state.value.phase == DropPhase.PROBING))
            throw DropException.NotReady()
        if (gatt?.hasGaia != true && rfcomm == null) throw DropException.UnsupportedCapability("GAIA")
    }

    private fun requireSource(probing: Boolean = false) {
        if (state.value.phase != DropPhase.READY && !(probing && state.value.phase == DropPhase.PROBING))
            throw DropException.NotReady()
        if (gatt?.hasSource != true) throw DropException.UnsupportedCapability("9ECA")
    }

    private fun onGaia(epoch: Long, bytes: ByteArray) {
        if (epoch != generation.get()) return
        val packet = GaiaCodec.decode(bytes) ?: return
        if (packet.type == GaiaCodec.RESPONSE) {
            val pending = pendingGaia
            if (pending?.generation == epoch && packet.vendor == pending.command.vendor &&
                packet.feature == pending.command.feature && packet.command == pending.command.command) {
                pending.reply.complete(packet)
            }
        } else if (packet.type == GaiaCodec.NOTIFICATION) {
            mutableEvents.tryEmit(DropEvent.GaiaNotification(packet))
        }
        if (packet.feature == GaiaIds.BATTERY && packet.command == GaiaIds.Battery.LEVELS)
            updateBattery(packet.payload)
    }

    private fun onSource(epoch: Long, bytes: ByteArray) {
        if (epoch != generation.get()) return
        val frame = SourceCodec.decode(bytes) ?: return
        if (frame.type == SourceCodec.RESPONSE) {
            val pending = pendingSource
            if (pending?.generation == epoch && pending.commandId == frame.commandId && pending.sequence == frame.sequence)
                pending.reply.complete(frame)
        } else {
            mutableEvents.tryEmit(DropEvent.SourceNotification(frame.commandId, frame.payload.copyOf()))
            runCatching {
                when (frame.commandId) {
                    129, 130 -> mutableState.update { it.copy(sourceStatus = SourceCodec.sourceStatus(frame.payload)) }
                    133 -> mutableState.update { it.copy(volume = SourceCodec.volume(frame.payload)) }
                    134 -> mutableState.update { it.copy(presetEq = SourceCodec.presetEq(frame.payload)) }
                    136 -> mutableState.update { it.copy(micGain = SourceCodec.micGain(frame.payload)) }
                }
            }
        }
    }

    private fun updateBattery(payload: ByteArray) {
        mutableState.update { current ->
            var left = current.battery.left
            var right = current.battery.right
            var case = current.battery.case
            for (index in 0 until payload.size - 1 step 2) {
                val value = payload[index + 1].toInt() and 0xff
                when (payload[index].toInt() and 0xff) {
                    1 -> left = value
                    2 -> right = value
                    3 -> case = value
                }
            }
            current.copy(battery = EarbudBattery(left, right, case))
        }
    }

    private fun onLost(epoch: Long) {
        if (epoch == generation.get()) lost?.complete(Unit)
    }

    private fun closeLinks() {
        generation.incrementAndGet()
        pollingJob?.cancel()
        pollingJob = null
        pendingGaia?.reply?.completeExceptionally(DropException.Disconnected())
        pendingSource?.reply?.completeExceptionally(DropException.Disconnected())
        pendingGaia = null
        pendingSource = null
        lost?.complete(Unit)
        lost = null
        gatt?.close()
        rfcomm?.close()
        gatt = null
        rfcomm = null
        ancPath = AncPath.UNKNOWN
    }

    private suspend fun awaitWake(millis: Long) { withTimeoutOrNull(millis) { wake.receive() } }
    private fun ensureOpen() { if (closed) throw DropException.Disconnected() }
    private fun CompletableDeferred<Unit>?.isCompletedSafe(): Boolean = this?.isCompleted ?: true

    private fun normalize(e: Exception): DropException = when (e) {
        is DropException -> e
        is SecurityException -> DropException.MissingPermission(DropPermissions.missing(context))
        else -> DropException.Transport("Earbud connection failed", e)
    }

    private fun report(e: Exception) {
        val error = normalize(e)
        mutableState.update { it.copy(phase = DropPhase.ERROR, error = error) }
        mutableEvents.tryEmit(DropEvent.Error(error))
    }
}
