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

/**
 * 应用自有的单耳机组合入口：选择音频身份、显式连接 GATT、创建 [DropController] 并执行重试和轮询。
 *
 * 公共 common API 不提供构造入口；Android 宿主通过平台工厂创建，并拥有注入的 [BtManager]。
 * 本类关闭自己的控制器和尝试端点，但不关闭管理器，也不管理其他设备的会话。
 * 不按品牌过滤，不配对、不建立系统音频连接，也不提供 RFCOMM 回退。
 *
 * 每次选择替换独立连接 epoch，新选择等待前一选择清理完成后才开始无线操作。
 * 同一设备上多个客户端/独立控制器的连接所有权并不隔离，宿主应避免共享受本类控制的端点。
 *
 * @param bt 外部拥有的蓝牙管理器；设备选择应使用此管理器返回的句柄。
 * @param associations 应用私有音频地址到 BLE 端点的成功关联记录。
 * @param options 传给每次新建控制器的协议配置；profile 匹配始终使用所选音频设备身份。
 */
class HeadsetClient internal constructor(
    private val bt: BtManager,
    private val associations: HeadsetAssociations,
    private val options: DropOptions = DropOptions(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lifecycle = Mutex()
    private val closed = MutableStateFlow(false)
    private val connection = MutableStateFlow<Connection?>(null)
    private val autoDeviceFilter = MutableStateFlow<(HeadsetDevice) -> Boolean>({ true })
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val mutableState = MutableStateFlow(HeadsetState())
    private val mutableEvents = MutableSharedFlow<HeadsetEvent>(extraBufferCapacity = 32)
    /**
     * 当前应用连接快照；功能数据位于 [HeadsetState.controls]，切换/清理不会保留旧会话读值。
     * 即使应用阶段为 READY，也须单独判断协议和能力，并处理操作时发生的断连。
     */
    val state: StateFlow<HeadsetState> = mutableState.asStateFlow()
    /**
     * 不重放的界面事件流，额外缓冲容量为 32，使用 `tryEmit` 尽力投递。
     * 无订阅者不会保存历史，慢订阅者可能错过事件；不可依赖它匹配协议回复或恢复当前状态。
     */
    val events: SharedFlow<HeadsetEvent> = mutableEvents.asSharedFlow()

    /**
     * 当前已采纳且仍就绪的 GAIA 控件；每次操作重新取值，不缓存跨重连引用。
     *
     * 此 getter 检查应用/控制阶段和会话身份，但不证明 GAIA 或具体能力存在。
     * @throws DropException.NotReady 尚未就绪、正在重连或已关闭；关闭后此 getter 仍抛 NotReady。
     */
    val gaia: GaiaControls get() = readyController().gaia
    /**
     * 当前已采纳且仍就绪的 9ECA 控件；实际方法仍会检查协议/特征支持。
     * 旧引用在绑定失效后不可复用，应在每次调用时重新获取。
     * @throws DropException.NotReady 无有效 READY 端点（包括本客户端已关闭）。
     */
    val source: SourceControls get() = readyController().source

    /**
     * 对象身份就是 facade epoch，不用地址或阶段判断新旧任务。
     * 新连接必须等前驱 [finished]，即使前驱在无线操作开始前被另一轮替换取消，也要完成清理链。
     */
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

    /**
     * 单次端点尝试及其资源；[ownsGatt] 在挂起 connect 前设置，保证尚未拿到 session 的取消也会 disconnect。
     * [adopted] 仅在控制器 READY、会话身份仍匹配且关联已保存后成立，探测完成不等于应用采纳。
     */
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

    /**
     * 刷新并返回系统当前已连接音频设备的快照，不扫描 LE、不建立 GATT、不按品牌或协议过滤。
     *
     * 本方法不改变当前选择；多候选时由调用方展示列表再调用 [connect]。
     * @return 管理器的原始音频候选，每项名称与关联标记均在本次映射时读取。
     * @throws DropException.Disconnected 本客户端已关闭。
     * @throws BtException 系统枚举所需权限、蓝牙可用性或传输操作失败；调用方取消原样传播。
     */
    suspend fun discoverConnectedDevices(): List<HeadsetDevice> {
        ensureOpen()
        return bt.refreshConnectedAudioDevices().map(::snapshot)
    }

    /**
     * 替换自动发现的候选策略并唤醒发现循环；默认接受全部音频设备。
     *
     * predicate 应为无副作用、非挂起的快照判断，仅在下一次自动选择前使用。
     * 不替换连接 epoch、不打断已选目标的连接/重连；原始发现、手动连接、
     * BLE 端点尝试、profile 和协议探测均不受它过滤。
     * @throws DropException.Disconnected 本客户端已关闭。
     */
    fun setAutoDeviceFilter(acceptDevice: (HeadsetDevice) -> Boolean) {
        ensureOpen()
        autoDeviceFilter.value = acceptDevice
        wake.trySend(Unit)
    }

    /**
     * 启动新的自动选择循环并替换既有连接；不是幂等的“若尚未启动”检查，也不挂起等待 READY。
     *
     * 经当前自动 predicate 接受的音频候选数为 0 时 IDLE、1 时尝试、多个时 SELECTION_REQUIRED。
     * 发现循环以合并的蓝牙事件唤醒或最多 5 秒等待再次刷新；目标失败后最多等待 4 秒再试，
     * 自动目标消失则回到发现；更改 predicate 不取消当前目标。
     * 失败通过 [state]/[events] 报告；[disconnect] 停止自动模式，需要显式调用本方法重启。
     * @throws DropException.Disconnected 本客户端已关闭。
     */
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

    /**
     * 通过本管理器取得地址句柄和当前名称快照，再按 [connect] 的手动选择策略连接。
     * 无需地址已出现在音频候选中；不会替调用方建立系统音频连接。
     * @param address 平台管理器接受的蓝牙地址，不是名称；地址校验由 [BtManager.device] 完成。
     * @throws BtException.InvalidDevice 地址无法取得有效设备句柄。
     * @throws DropException.Disconnected 客户端已关闭；其他失败和取消规则同设备重载。
     */
    suspend fun connect(address: String) {
        ensureOpen()
        connect(snapshot(bt.device(address)))
    }

    /**
     * 替换当前选择并等待首次有效 READY；显示及 profile 匹配保留音频身份，通讯可使用不同 BLE 端点。
     *
     * 尝试顺序为缓存关联、所选地址、已配对 LE/DUAL 同名设备、LE 扫描结果，按地址去重。
     * 同名仅是应用候选策略，不证明物理身份。首次手动连接失败结束本轮并抛实际异常；
     * 首次成功后的会话丢失则在本客户端自有任务中继续重连，方法返回不结束连接生命周期。
     *
     * 调用方在等待时取消会失效本轮选择，在不可取消清理中关闭控制器并显式断开尝试 GATT，
     * 等待清理完成后原样传播取消。返回后取消原调用方不会自动撤销已由客户端接管的连接。
     *
     * @param device 同一注入管理器的选择快照；关联标记会重新从存储读取。
     * @throws DropException.Disconnected 已关闭或本轮在 READY 前被替换/断开。
     * @throws BtException 实际蓝牙失败；扫描缺权限可跳过，不会抹掉直接连接失败。
     * @throws DropException 实际控制探测失败；非“不支持”的失败优先于候选的 UnsupportedDevice。
     */
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

    /**
     * 停止自动/手动连接生命周期，立即失效当前选择并重置状态，然后等待该轮清理完成。
     *
     * 等待部分在不可取消上下文中执行；也会清理尚未返回会话的 GATT 连接尝试。
     * 不关闭注入管理器或其他设备，不删除关联记录；可再次显式启动/连接，关闭后调用也可作空清理。
     * 若需要确定本轮资源已释放，使用此挂起方法，而非仅依赖 [close] 返回。
     */
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

    /**
     * 幂等终结本客户端：同步设置关闭标记、失效 epoch、关闭当前控制器并重置为默认 IDLE。
     *
     * 非挂起调用不等待原生 GATT 断开；连接协程的不可取消 finally 随后释放尝试资源。
     * 不关闭注入管理器或其他设备，宿主仍负责管理器最终释放。不能再次 start/connect/discover；
     * [gaia]/[source] getter 关闭后抛 NotReady，而已取得的旧控件因绑定失效抛 Disconnected。
     */
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
        // close 同步失效 epoch，不等待此锁；发布后必须再次检查关闭标记。
        if (closed.value) {
            connection.compareAndSet(next, null)
            next.job.cancel()
        }
        next to previous
    }

    private fun launchConnection(next: Connection, previous: Connection?) {
        // UNDISPATCHED 确保 replacement 与 launch 之间 close 取消 job 时仍安装 finally。
        // 等待前驱清理与无线操作均在 lifecycle 锁外，避免切换/断开被慢连接阻塞。
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
                val rawCandidates = discoverConnectedDevices()
                val candidates = rawCandidates.filter(autoDeviceFilter.value)
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

    /**
     * 首次手动失败结束本轮；自动首次失败和任何曾 READY 的目标继续重试。
     * 自动目标以系统音频地址事实为边界，GATT 断连不等同于系统音频消失；手动目标没有音频观察约束。
     */
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
                // 历史偏好可能含无效地址，忽略该记录，不把它当作实际端点尝试。
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
            // 扫描缺权限只跳过扫描阶段，不否定已经执行的直接 GATT 尝试。
            emptyList()
        } catch (e: Exception) {
            lastFailure = e
            emptyList()
        }
        for (endpoint in scanned) tryCandidate(endpoint)?.let { return it }
        // 只有没有其他真实失败时，候选的 UnsupportedDevice 才是最终失败；不能把超时改写成不支持。
        throw lastFailure ?: unsupported ?: DropException.Disconnected()
    }

    /**
     * 先安装独立 Attempt 与观察，再调用 connect；即使 connect 未返回也必须具备显式 disconnect 所有权。
     * 控制器使用 selected 的 profile 身份，仅在会话仍匹配且控制 READY 时保存关联并采纳。
     * 任意失败/取消路径都先 releaseAttempt，再允许下一候选或新选择进入无线操作。
     */
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

    /**
     * 不可取消清理先失效本轮 attempt、关闭控制器与订阅，再在锁外 disconnect。
     * 不要求 session 已返回：泛型 GATT 的独立连接任务不会因其调用方取消而自动结束。
     * epoch 条件发布确保旧清理只能释放自己的资源，不能覆盖新选择的状态。
     */
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
            // 仅取消 connect 的等待者不会取消管理器自有无线任务，必须显式 disconnect。
            attempt.endpoint.gatt.disconnect()
        }
    }

    /** 转发同时检查连接 epoch、attempt 身份和已知 session；READY 必须等应用采纳，不能仅凭 probe 完成。 */
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

    /**
     * 每次成功采纳后尝试初读 ANC（仅能力集合非空），并在 GAIA 可用时立即读电量、每 30 秒再次读。
     * 两个自有任务随 attempt 清理取消；普通读取失败保留未知/最近读值，绝不写入猜测成功值。
     */
    private fun startPolling(current: Connection, attempt: Attempt) {
        val polling = CoroutineScope(current.job + Dispatchers.Default)
        attempt.observers += polling.launch {
            if (attempt.controller.state.value.capabilities.ancModes.isNotEmpty()) {
                try {
                    if (isCurrentAttempt(current, attempt) && hasSession(attempt)) attempt.controller.gaia.getAncMode()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // 初读失败不构造模式，保留未知或当前已观察模式。
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
                        // 轮询失败不能用构造的电量覆盖最近真实读值。
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

    /** 此 facade 的 getter 始终以 NotReady 表达不可用（含 close）；旧控制器控件的失效异常另属绑定层。 */
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
        // close 可能不等待 lifecycle 就使 epoch 失效，最后一次写入仍必须恢复默认状态。
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
        // 丢弃刚结束尝试自身引起的合并通知，避免立即自唤醒重试；后续事件仍可提前唤醒。
        wake.tryReceive()
        withTimeoutOrNull(timeoutMillis) { wake.receiveCatching() }
    }
}
