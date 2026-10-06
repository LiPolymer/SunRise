package ink.lipoly.app.sunrise.drop

import ink.lipoly.app.sunrise.blueConnector.BtDevice
import ink.lipoly.app.sunrise.blueConnector.BtRfcomm
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
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 观察 [device] 的现有 GATT 状态，为每个已连接会话创建独立协议绑定。
 *
 * 构造可以早于或晚于显式 GATT 连接；构造、[awaitReady]、[close] 均不扫描、配对、
 * 连接或断开蓝牙。自动重连、选择与电量轮询属于应用，不在此类中。
 * 不同设备的控制器各自拥有事务锁、回复槽和状态；同设备重复控制器并行操作不在保证范围内。
 *
 * @property device 通讯设备；其管理器与 GATT 会话由调用方持有并释放。
 * @param options ANC/DAC 设备编号映射选项。
 * @param profileDevice 仅提供配置匹配的地址/名称；音频身份不同于 BLE 地址时可传音频设备，
 * 不会据此切换连接。每次新绑定读取当时的地址与 info.name。
 * 当 [profileDevice] 地址不同于 [device] 时，其 [BtRfcomm] 句柄随绑定转交结构化 EQ 写入；
 * 同地址时不猜测对端提供 SPP 记录，EQ 写入继续使用 BLE GATT。
 */
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
    /** 当前绑定的功能快照；断连、关闭重置为默认 IDLE。ERROR 不意味着 GATT 已关闭。 */
    val state: StateFlow<DropState> = mutableState.asStateFlow()
    /** 无重放的瞬时通知；发布为 best-effort，慢订阅者应以 [state] 快照恢复，不用于请求匹配。 */
    val events: SharedFlow<DropEvent> = mutableEvents.asSharedFlow()

    /**
     * 当前 READY 绑定的 GAIA 控件；不保证该绑定含 GAIA。
     * 未 READY 抛 [DropException.NotReady]，已关闭抛 [DropException.Disconnected]；
     * 换会话后必须重新取得，旧引用不能复用。
     */
    val gaia: GaiaControls get() = readyBinding().gaia
    /** 当前 READY 绑定的 9ECA 控件；就绪/关闭/旧引用约束与 [gaia] 相同，不保证含 9ECA。 */
    val source: SourceControls get() = readyBinding().source

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            device.gatt.state.collect { synchronizeBinding()?.startInitialization() }
        }
    }

    /**
     * 仅初始化/等待当前已经 CONNECTED 的 GATT 会话。
     * 与状态观察共享同一绑定及初始化 deferred，同会话只探测一次，无线 I/O 不持生命周期锁。
     * 调用方取消只取消自己的等待，不取消共享初始化或 GATT；绑定消失则等待失败。
     *
     * @throws DropException.NotReady 没有已连接会话，不会等待未来连接。
     * @throws DropException.Disconnected 已关闭、等待中断连或会话替换。
     * @throws DropException 初始化失败的实际协议/归一化传输异常；同会话不自动重新探测。
     */
    suspend fun awaitReady() {
        ensureOpen()
        val current = synchronizeBinding()
        ensureOpen()
        if (current == null) throw DropException.NotReady()
        current.startInitialization()
        current.ready.await()
        if (!isCurrent(current) || !current.isOpen) throw DropException.Disconnected()
    }

    /**
     * 幂等同步关闭自有绑定/协程、失败化等待并重置状态。
     * 不断开 GATT、不关闭管理器、不为其他会话关闭 CCCD；已启动的原生操作仍由会话排空。
     */
    fun close() {
        if (!closed.compareAndSet(expect = false, update = true)) return
        binding.getAndUpdate { null }?.close()
        scope.cancel()
        mutableState.value = DropState()
    }

    /**
     * 短临界区只管理绑定身份；使用最新 GATT 快照，防止迟到的 state emission 重新安装旧会话。
     * 初始化在锁外开始；同步 close 可越过锁，因此安装后再次核对 closed 与 session epoch。
     */
    private suspend fun synchronizeBinding(): DropControlBinding? = lifecycle.withLock {
        if (closed.value) return@withLock null
        // 读取最新快照，避免之前的发射已被新会话状态超越。
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
            // 经典 EQ 通道只属于经典身份：端点与配置身份同地址时不猜测对端存在 SPP 记录。
            classicEq = profileDevice.rfcomm.takeIf { profileDevice.address != device.address },
        )
        binding.value = next
        // close 同步执行且不等待此锁；安装后必须再次核对关闭与会话身份。
        if (closed.value || !isCurrent(next)) {
            binding.compareAndSet(next, null)
            next.close()
            mutableState.update { if (closed.value || binding.value == null) DropState() else it }
            return@withLock null
        }
        publishState(next)
        next
    }

    /** 引用身份与 session.id 双重 epoch 守卫；旧绑定不能发布新会话的状态或通知。 */
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
