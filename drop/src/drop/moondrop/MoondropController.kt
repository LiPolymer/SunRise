package ink.lipoly.app.sunrise.drop.moondrop

import ink.lipoly.app.sunrise.bluetooth.BtDevice
import ink.lipoly.app.sunrise.bluetooth.GattController
import ink.lipoly.app.sunrise.bluetooth.GattPhase
import ink.lipoly.app.sunrise.bluetooth.GattSession
import ink.lipoly.app.sunrise.bluetooth.RfcommStream
import ink.lipoly.app.sunrise.drop.AncMode
import ink.lipoly.app.sunrise.drop.BatteryControls
import ink.lipoly.app.sunrise.drop.DropEvent
import ink.lipoly.app.sunrise.drop.DropOptions
import ink.lipoly.app.sunrise.drop.DropProfile
import ink.lipoly.app.sunrise.drop.DropState
import ink.lipoly.app.sunrise.drop.EarbudBatteryReading
import ink.lipoly.app.sunrise.drop.IBudsController
import ink.lipoly.app.sunrise.drop.NoiseControls
import ink.lipoly.app.sunrise.drop.moondrop.gaia.GaiaControls
import ink.lipoly.app.sunrise.drop.moondrop.source.SourceControls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 水月雨（GAIA / 9ECA）实现的单会话控制器：持有一个 [DropControlBinding] 及其传输资源。
 *
 * 能力通过接口暴露：[GaiaControls]、[SourceControls] 直接委托给本绑定的控件实现，
 * [BatteryControls] / [NoiseControls] 是 GAIA 电量与降噪的投影，调用方用 `as?` 取得。
 * 一次传输会话一个实例；换设备或重连必须关闭旧实例再新建，旧实例的所有调用随后抛
 * [ink.lipoly.app.sunrise.drop.DropException.Disconnected]。
 *
 * @param binding 本会话的协议绑定；其状态与事件经构造函数注入的流对外发布。
 * @param ownedGatt 本实例打开并负责断开的 GATT 入口；借用会话时为 null。
 * @param ownedSession 本实例打开的会话身份；GATT 断开或替换出其他会话时关闭绑定。
 * @param classicEq 经典 RFCOMM 通道；非空时结构化 EQ 写入走经典链路。
 */
internal class MoondropController(
    private val binding: DropControlBinding,
    private val ownedGatt: GattController?,
    private val ownedSession: GattSession?,
    private val classicEq: RfcommStream?,
    private val scope: CoroutineScope,
    private val mutableState: MutableStateFlow<DropState>,
    private val mutableEvents: MutableSharedFlow<DropEvent>,
    private val closed: MutableStateFlow<Boolean>,
) : IBudsController,
    GaiaControls by binding.gaia,
    SourceControls by binding.source,
    BatteryControls,
    NoiseControls {

    override val state: StateFlow<DropState> = mutableState.asStateFlow()
    override val events: SharedFlow<DropEvent> = mutableEvents.asSharedFlow()

    override suspend fun readBattery(): EarbudBatteryReading = binding.gaia.getBattery()
    override suspend fun readNoise(): AncMode = binding.gaia.getAncMode()
    override suspend fun writeNoise(mode: AncMode): AncMode = binding.gaia.setAncMode(mode)

    /**
     * 幂等关闭绑定、自有 scope 与本实例打开的传输；状态恢复默认快照。
     * 已开始的调用因绑定 retired 转为 Disconnected，原生操作仍由会话排空。
     */
    override suspend fun close() {
        if (!closed.compareAndSet(expect = false, update = true)) return
        binding.close()
        scope.cancel()
        mutableState.value = DropState()
        withContext(NonCancellable) {
            runCatching { classicEq?.close() }
            ownedGatt?.let { gatt -> runCatching { gatt.disconnect() } }
        }
    }

    init {
        // 只有自己打开的传输需要跟随断开；借用会话由调用方决定何时退休。
        ownedGatt?.let { gatt ->
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                gatt.state.collect { snapshot ->
                    if (closed.value) return@collect
                    if (snapshot.phase != GattPhase.CONNECTED || snapshot.session !== ownedSession) {
                        binding.close()
                        mutableState.value = DropState()
                    }
                }
            }
        }
    }
}

/**
 * 连接 [device] 的 GATT、探测协议并返回 READY 的控制器；传输由返回实例拥有。
 *
 * 不扫描、不配对、不自动重连；失败会在断开本次连接后原样抛出。
 * @param device 实际通讯端点。
 * @param options ANC/DAC 设备编号映射选项。
 * @param profileDevice 仅提供配置匹配的地址/名称，可与通讯端点不同；同地址时不猜测 SPP 记录。
 * @throws ink.lipoly.app.sunrise.bluetooth.BtException 连接或服务发现失败。
 * @throws ink.lipoly.app.sunrise.drop.DropException 探测失败；本次连接已被断开。
 */
suspend fun openMoondrop(
    device: BtDevice,
    options: DropOptions = DropOptions(),
    profileDevice: BtDevice = device,
): IBudsController {
    val gatt = device.gatt
    val session = gatt.connect()
    return try {
        val profile = DropProfiles.resolve(options, profileDevice.address, profileDevice.info.value.name)
        // 经典 EQ 通道只属于经典身份：端点与配置身份同地址时不猜测对端存在 SPP 记录。
        val classicEq = profileDevice.rfcomm.takeIf { profileDevice.address != device.address }
        openMoondrop(
            session = session,
            profile = profile,
            classicEq = classicEq,
            ownedGatt = gatt,
            ownedSession = session,
        )
    } catch (e: Throwable) {
        withContext(NonCancellable) { runCatching { gatt.disconnect() } }
        throw e
    }
}

/**
 * 借用调用方已建立的 [session] 完成探测；不等同于公开入口，仅供内部与测试使用。
 * @param current 额外的 epoch 守卫，返回 false 时该绑定不得读写。
 */
internal suspend fun openMoondrop(
    session: GattSession,
    profile: DropProfile,
    classicEq: RfcommStream? = null,
    ownedGatt: GattController? = null,
    ownedSession: GattSession? = null,
    current: () -> Boolean = { true },
): MoondropController {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val closed = MutableStateFlow(false)
    val mutableState = MutableStateFlow(DropState())
    val mutableEvents = MutableSharedFlow<DropEvent>(extraBufferCapacity = 32)
    val binding = DropControlBinding(
        session = session,
        parentScope = scope,
        resolvedProfile = profile,
        isCurrent = { _ -> !closed.value && current() },
        publishState = { source -> if (!closed.value) mutableState.value = source.state.value },
        publishEvent = { _, event -> if (!closed.value) mutableEvents.tryEmit(event) },
        classicEq = classicEq,
    )
    val controller = MoondropController(
        binding = binding,
        ownedGatt = ownedGatt,
        ownedSession = ownedSession,
        classicEq = classicEq,
        scope = scope,
        mutableState = mutableState,
        mutableEvents = mutableEvents,
        closed = closed,
    )
    try {
        binding.startInitialization()
        binding.ready.await()
        return controller
    } catch (e: Throwable) {
        binding.close()
        scope.cancel()
        withContext(NonCancellable) { runCatching { classicEq?.close() } }
        throw e
    }
}
