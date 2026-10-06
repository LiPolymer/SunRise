package ink.lipoly.app.sunrise.drop

import ink.lipoly.app.sunrise.blueConnector.BtException
import ink.lipoly.app.sunrise.blueConnector.BtRfcomm
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

/**
 * 单个 GATT epoch 的协议事务、初始化、控件与状态所有者；不可跨会话复用。
 * [session] 的对象身份及 id 由控制器核对，任何状态写入、回复与事件均受 isCurrent 守卫。
 * 自有 scope 负责初始化和收集，lifetime 只取消外部调用的子 Job，不关闭原生 GATT。
 */
internal class DropControlBinding(
    val session: GattSession,
    parentScope: CoroutineScope,
    private val resolvedProfile: DropProfile,
    private val isCurrent: (DropControlBinding) -> Boolean,
    private val publishState: (DropControlBinding) -> Unit,
    private val publishEvent: (DropControlBinding, DropEvent) -> Unit,
    /** 经典 RFCOMM 通道；非空时结构化 EQ 写入走经典链路，BLE 只负责读取与控件。 */
    classicEq: BtRfcomm? = null,
) : DropControlSession {
    // lifetime 不含初始化子任务，解绑可立即取消外部调用，不必等初始化协程收尾。
    private val lifetime = Job(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(SupervisorJob(parentScope.coroutineContext[Job]) + Dispatchers.Default)
    private val closed = MutableStateFlow(false)
    private val started = MutableStateFlow(false)
    /** GAIA 和 9ECA 共用一个绑定内事务锁；不同设备的锁相互独立，无全局 pending 槽。 */
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
    /** 状态观察与 awaitReady 共用的初始化结果；每个 epoch 仅启动一次，调用方取消不取消它。 */
    val ready = CompletableDeferred<Unit>()
    val gaia: GaiaControls = GaiaControlsImpl(this, classicEq)
    val source: SourceControls = SourceControlsImpl(this)
    val isOpen: Boolean get() = !closed.value && lifetime.isActive

    private data class PendingGaia(
        val command: GaiaCommand,
        val reply: CompletableDeferred<GaiaPacket>,
        val expectedPeqRange: IntRange? = null,
    )
    private data class PendingSource(val commandId: Int, val sequence: Int, val reply: CompletableDeferred<SourceFrame>)
    private class BindingDisconnectedCancellation : CancellationException("Drop binding disconnected")
    /** One object per binding; its owner is only set while holding transactions. */
    private val gaiaTransaction = object : GaiaTransaction {
        var owner: Job? = null
        private suspend fun checkOwner() {
            check(owner != null && currentCoroutineContext().job === owner) {
                "GAIA transaction used outside its owning coroutine"
            }
            ensureBound()
        }
        override val maxWriteSize: Int get() {
            check(owner != null) { "GAIA transaction has ended" }
            ensureBound()
            return session.mtu.value - 3
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
        lifetime.invokeOnCompletion { close() }
    }

    /** 原子抢占初始化启动权；失败发布 ERROR 后退休绑定，同会话不在此自动重试。 */
    fun startInitialization() {
        if (!started.compareAndSet(expect = false, update = true)) return
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

    /** 失败化初始化/回复并取消调用与收集；幂等，不关闭 session 或撤销其 CCCD。 */
    fun close() {
        if (!closed.compareAndSet(expect = false, update = true)) return
        val failure = DropException.Disconnected()
        ready.completeExceptionally(failure)
        pendingGaia.value?.reply?.completeExceptionally(failure)
        pendingSource.value?.reply?.completeExceptionally(failure)
        lifetime.cancel(BindingDisconnectedCancellation())
        scope.cancel(BindingDisconnectedCancellation())
        // 通知配置与已开始的原生 GATT 操作属于 session，而非此绑定。
    }

    override fun profile(): DropProfile {
        ensureBound()
        return resolvedProfile
    }

    override fun ancPath(): AncPath {
        ensureBound()
        return selectedAncPath
    }

    /** 修改前后都核对 epoch；旧 GET/通知不能将旧状态写入新绑定。 */
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

    /**
     * 为传输入口建立调用方的子 Job，并注册 lifetime 完成监听。
     * 解绑仅取消该子 Job，转为 Disconnected；调用方取消保持 CancellationException。
     * 原生已开始操作继续由 session 排空，直接 capability/info 读取也受同一生命周期保护。
     */
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
    } catch (_: BindingDisconnectedCancellation) {
        currentCoroutineContext().ensureActive()
        throw DropException.Disconnected()
    } catch (e: BtException) {
        throw normalize(e)
    }

    /**
     * 先订阅原始流再启用 CCCD；响应通知是 mandatory，额外 data/notification 与 MTU247 为可选。
     * 可选操作失败只在 epoch 仍有效时忽略；原生超时使会话失效时不得发布 READY。
     */
    private suspend fun initialize() = inBinding {
        if (protocols.isEmpty()) throw DropException.UnsupportedDevice()
        // UNDISPATCHED 保证在任何 CCCD/write 可能回包前已经订阅 SharedFlow。
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
                        // 断连可能超越解码，退休帧不能继续修改状态。
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
            // 会话仍有效时拒绝可 best-effort；原生超时/连接丢失不可忽略。
            if (!isOpen || !isCurrent(this)) throw normalize(e)
        }
        ensureBound()
    }

    /**
     * GAIA 能力最多读取八页，完整性独立于是否 READY；ANC 优先 AC→V2→V1，未知时逐个 GET 探测。
     * 9ECA 优先读取 info，失败退 GET_FW_VERSION；capability 特征不参与协议存在性判断。
     * 已存在协议的探测完整性合取；失败且会话仍活跃可保留部分能力并 READY。
     */
    private suspend fun probe() {
        var features = emptySet<Int>()
        var gaiaComplete = DropProtocol.GAIA_BLE !in protocols
        if (DropProtocol.GAIA_BLE in protocols) {
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

    /** Installs pending before writing and clears it by identity even on cancellation. */
    private suspend fun requestGaia(command: GaiaCommand, probing: Boolean): GaiaPacket = inBinding {
        transactions.withLock {
            requireProtocol(DropProtocol.GAIA_BLE, probing)
            requestGaiaLocked(command)
        }
    }

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
            withTimeoutOrNull(6_000.milliseconds) { pending.reply.await() }
                ?: throw DropException.Timeout("GAIA ${command.feature}/${command.command}")
        } finally {
            pendingGaia.compareAndSet(pending, null)
        }
    }

    private suspend fun sendGaiaLocked(command: GaiaCommand) {
        ensureBound()
        try {
            session.write(gaiaCommand!!, GaiaCodec.encode(command))
        } catch (e: BtException) {
            throw normalize(e)
        }
        ensureBound()
    }

    override suspend fun <T> withGaiaTransaction(block: suspend GaiaTransaction.() -> T): T = inBinding {
        transactions.withLock {
            requireProtocol(DropProtocol.GAIA_BLE, probing = false)
            gaiaTransaction.owner = currentCoroutineContext().job
            try {
                block(gaiaTransaction)
            } finally {
                gaiaTransaction.owner = null
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

    /** 只在事务锁内完成原生写，不建立回复槽；SET ANC、关机及 sendRaw 使用此路径。 */
    override suspend fun sendGaia(command: GaiaCommand) = inBinding {
        transactions.withLock {
            requireProtocol(DropProtocol.GAIA_BLE, probing = false)
            sendGaiaLocked(command)
        }
    }

    override suspend fun requestSource(commandId: Int, payload: ByteArray): ByteArray =
        requestSource(commandId, payload, probing = false)

    /** commandId 与循环字节 sequence 双匹配；写入之后六秒回复期限不等于底层原生操作期限。 */
    private suspend fun requestSource(commandId: Int, payload: ByteArray, probing: Boolean): ByteArray = inBinding {
        transactions.withLock {
            requireProtocol(DropProtocol.SOURCE_9ECA, probing)
            val pending = PendingSource(commandId, sequence++ and 0xff, CompletableDeferred())
            pendingSource.value = pending
            try {
                ensureBound()
                session.write(sourceCommand!!, SourceCodec.encode(commandId, pending.sequence, payload))
                (withTimeoutOrNull(6_000.milliseconds) { pending.reply.await() }
                    ?: throw DropException.Timeout("9ECA command $commandId")).payload
            } finally {
                pendingSource.compareAndSet(pending, null)
            }
        }
    }

    /** 直接可读 capability 不走协议回复槽；缺失/不可读特征为 UnsupportedCapability，不否定 9ECA。 */
    override suspend fun readSourceCapability(): ByteArray = inBinding {
        requireProtocol(DropProtocol.SOURCE_9ECA, probing = false)
        session.read(sourceCapability ?: throw DropException.UnsupportedCapability("9ECA capability characteristic"))
    }

    override suspend fun readSourceInfo(): ByteArray = readSourceInfo(probing = false)

    /** 初始化及显式固件查询共用的直接读取入口，仍必须受绑定 lifetime 保护。 */
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

    /** 回复完成内部 pending；通知事件独立发布，电量字节对合并到当前绑定快照。 */
    private fun onGaia(bytes: ByteArray) {
        if (!isOpen || !isCurrent(this)) return
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
                    pending.reply.complete(packet)
                }
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

    /** RESPONSE 不作通知；通知 129/130、133、134、136 分别更新音源、音量、EQ、麦克风。 */
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
                // 畸形非请求通知不使正在等待的请求失效。
            }
        }
    }

    /**
     * 只对通用传输错误转换；Timeout/Disconnected/UnsupportedOperation/InvalidGattHandle/
     * PacketTooLarge 分别转控制异常，其余保留原异常为 Transport.cause；调用方取消在外层透传。
     */
    override fun normalizeTransport(error: Exception): DropException = normalize(error)

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
