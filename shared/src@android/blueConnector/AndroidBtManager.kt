package ink.lipoly.app.sunrise.blueConnector

import android.Manifest
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Android 真实蓝牙管理器，持有应用上下文、广播和 SupervisorJob + IO 作用域。
 *
 * cache 以规范大写地址保存稳定句柄，observed 独立记录实际观测，区分 device() 与发现。
 * lock 保护同步身份/关闭事实；audioMutex 串行音频快照，发现另有扫描锁，GATT 逐设备独立。
 * 管理器事件经自有无界 channel 串行发布（不是 GATT 的有界原始通知队列），无历史重放。
 * @property context 工厂提供的 applicationContext，不依附 Activity。
 */
internal class AndroidBtManager(internal val context: Context) : BtManager {
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    @Volatile internal var isClosed = false
        private set
    private val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val cache = LinkedHashMap<String, AndroidBtDevice>()
    private val observed = mutableSetOf<String>()
    private val discovery = AndroidBtDiscovery(this)
    private val audioMutex = Mutex()
    private val mutableAvailability = MutableStateFlow(readAvailability())
    private val mutableDevices = MutableStateFlow<List<BtDevice>>(emptyList())
    private val mutableAudioDevices = MutableStateFlow<List<BtDevice>>(emptyList())
    private val mutableEvents = MutableSharedFlow<BtEvent>(extraBufferCapacity = 64)
    private val eventQueue = Channel<BtEvent>(Channel.UNLIMITED)
    override val availability = mutableAvailability.asStateFlow()
    override val devices = mutableDevices.asStateFlow()
    override val connectedAudioDevices = mutableAudioDevices.asStateFlow()
    override val events = mutableEvents.asSharedFlow()

    /**
     * 监听无线状态、ACL、名称/配对与 A2DP/HEADSET 变化，在后台刷新事实而非回调里等待无线。
     * 经典 ACL 断开不直接断开 GATT；GATT 生命周期由对应原生 callback 决定。
     */
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (isClosed) return
            @Suppress("DEPRECATION")
            val nativeDevice = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            val radioState = if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            } else null
            scope.launch {
                try {
                    refreshAvailability(
                        if (radioState == BluetoothAdapter.STATE_OFF || radioState == BluetoothAdapter.STATE_TURNING_OFF)
                            BtAvailability.DISABLED else null,
                    )
                    if (nativeDevice != null && availability.value == BtAvailability.ENABLED) observe(nativeDevice)
                    if (availability.value == BtAvailability.ENABLED) refreshConnectedAudioDevices()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: BtException) {
                    if (!isClosed) publish(BtEvent.OnError(e, this@AndroidBtManager))
                }
            }
        }
    }

    init {
        scope.launch { for (event in eventQueue) mutableEvents.emit(event) }
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothDevice.ACTION_NAME_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            else context.registerReceiver(receiver, filter)
        } catch (e: Exception) {
            eventQueue.close()
            scope.cancel()
            throw BtException.Transport("Unable to register Bluetooth state receiver", e)
        }
    }

    /** 无适配器优先为 UNAVAILABLE；缺 CONNECT 权限或读取受拒时为 UNKNOWN，不触发授权 UI。 */
    private fun readAvailability(): BtAvailability {
        if (isClosed) return BtAvailability.CLOSED
        if (adapter == null) return BtAvailability.UNAVAILABLE
        if (BtPermissions.missing(context, false).isNotEmpty()) return BtAvailability.UNKNOWN
        return try {
            if (adapter.isEnabled) BtAvailability.ENABLED else BtAvailability.DISABLED
        } catch (_: SecurityException) {
            BtAvailability.UNKNOWN
        }
    }

    /** 非 ENABLED 状态使音频快照清空、发现请求和所有设备会话失效，但保留稳定设备身份。 */
    private fun refreshAvailability(forced: BtAvailability? = null) {
        val value = forced ?: readAvailability()
        val handles = synchronized(lock) {
            if (isClosed) return
            if (mutableAvailability.value == value) return
            mutableAvailability.value = value
            publish(BtEvent.OnBluetoothStateChanged(value, this))
            if (value != BtAvailability.ENABLED) {
                mutableAudioDevices.value = emptyList()
                cache.values.toList()
            } else emptyList()
        }
        if (value != BtAvailability.ENABLED) {
            discovery.invalidate(BtException.Disconnected())
            handles.forEach { it.gatt.invalidate() }
        }
    }

    /**
     * 每次无线操作检查关闭、权限和适配器开启事实；检查成功可将 availability 刷新为 ENABLED。
     * @param scan 是否额外需要扫描权限。
     * @throws BtException 关闭、缺权限或无线不可用，不隐式开启蓝牙。
     */
    internal fun requireBluetooth(scan: Boolean = false): BluetoothAdapter {
        if (isClosed) throw BtException.Disconnected()
        val missing = BtPermissions.missing(context, scan)
        if (missing.isNotEmpty()) throw BtException.MissingPermission(missing)
        val result = adapter ?: throw BtException.BluetoothUnavailable()
        if (!platformOperation("read Bluetooth state", scan) { result.isEnabled }) throw BtException.BluetoothUnavailable()
        synchronized(lock) {
            if (isClosed) throw BtException.Disconnected()
            if (mutableAvailability.value != BtAvailability.ENABLED) {
                mutableAvailability.value = BtAvailability.ENABLED
                publish(BtEvent.OnBluetoothStateChanged(BtAvailability.ENABLED, this))
            }
        }
        return result
    }

    /** 平台异常统一映射；取消和已有 BtException 不改写，SecurityException 再检查完整权限名。 */
    internal fun <T> platformOperation(operation: String, scan: Boolean = false, block: () -> T): T {
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: BtException) {
            throw e
        } catch (e: SecurityException) {
            val missing = BtPermissions.missing(context, scan)
            if (missing.isNotEmpty()) throw BtException.MissingPermission(missing)
            // 平台调用与复查之间可能撤销权限；保留本操作类别的完整所需权限名。
            val required = if (Build.VERSION.SDK_INT >= 31) {
                if (scan) setOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
                else setOf(Manifest.permission.BLUETOOTH_CONNECT)
            } else if (scan) setOf(Manifest.permission.ACCESS_FINE_LOCATION) else emptySet()
            if (required.isNotEmpty()) throw BtException.MissingPermission(required)
            throw BtException.Transport("Bluetooth operation denied: $operation", e)
        } catch (e: Exception) {
            throw BtException.Transport("Bluetooth operation failed: $operation", e)
        }
    }

    /** 只规范/缓存地址句柄，不观测信息或发发现事件；取句柄不要求先扫描。 */
    override fun device(address: String): BtDevice {
        val normalized = normalizeAddress(address)
        return synchronized(lock) {
            if (isClosed) throw BtException.Disconnected()
            cache[normalized] ?: run {
                val bluetoothAdapter = adapter ?: throw BtException.BluetoothUnavailable()
                val native = platformOperation("get device handle") { bluetoothAdapter.getRemoteDevice(normalized) }
                addHandle(native, normalized)
            }
        }
    }

    private fun addHandle(native: BluetoothDevice, address: String): AndroidBtDevice {
        val result = AndroidBtDevice(this, native, address)
        cache[address] = result
        mutableDevices.value = cache.values.sortedBy { it.address }
        return result
    }

    /**
     * 读取系统事实，名称为空时回退到扫描名；首次实际观测发 OnDiscovered，
     * 后续仅信息发生变化才发 OnDeviceChanged，按地址而非名称合并。
     */
    internal fun observe(native: BluetoothDevice, scannedName: String? = null): AndroidBtDevice {
        requireBluetooth()
        val address = normalizeAddress(native.address)
        val info = platformOperation("read device information") {
            BtDeviceInfo(
                name = native.name ?: scannedName,
                kind = when (native.type) {
                    BluetoothDevice.DEVICE_TYPE_CLASSIC -> BtDeviceKind.CLASSIC
                    BluetoothDevice.DEVICE_TYPE_LE -> BtDeviceKind.LE
                    BluetoothDevice.DEVICE_TYPE_DUAL -> BtDeviceKind.DUAL
                    else -> BtDeviceKind.UNKNOWN
                },
                bondState = when (native.bondState) {
                    BluetoothDevice.BOND_BONDING -> BtBondState.BONDING
                    BluetoothDevice.BOND_BONDED -> BtBondState.BONDED
                    else -> BtBondState.NONE
                },
            )
        }
        return synchronized(lock) {
            if (isClosed) throw BtException.Disconnected()
            val result = cache[address] ?: addHandle(native, address)
            val changed = result.updateInfo(info)
            if (observed.add(address)) publish(BtEvent.OnDiscovered(result, this))
            else if (changed) publish(BtEvent.OnDeviceChanged(result, this))
            result
        }
    }

    /** 串行刷新音频事实；返回值与公开快照采用同一去重/排序列表，不做应用候选筛选。 */
    override suspend fun refreshConnectedAudioDevices(): List<BtDevice> = audioMutex.withLock {
        refreshAvailability()
        val result = discovery.connectedAudioDevices().map { observe(it) }
        synchronized(lock) {
            requireBluetooth()
            mutableAudioDevices.value = result
        }
        result
    }

    override suspend fun bondedDevices(): List<BtDevice> {
        val bluetoothAdapter = requireBluetooth()
        return platformOperation("enumerate bonded devices") { bluetoothAdapter.bondedDevices.toList() }
            .map { observe(it) }.sortedBy { it.address }
    }

    override suspend fun scanLe(name: String?, address: String?, timeoutMillis: Long): List<BtDevice> {
        require(timeoutMillis > 0) { "Scan timeout must be positive" }
        val normalized = address?.let(::normalizeAddress)
        return discovery.scanLe(name, normalized, timeoutMillis)
    }

    /** GATT 状态发布后，带 error 的快照额外发布 OnError；直接调用失败不保证走此路径。 */
    internal fun publishGatt(device: AndroidBtDevice, state: GattState) {
        publish(BtEvent.OnGattStateChanged(device, state, this))
        state.error?.let { publish(BtEvent.OnError(it, this, device)) }
    }

    private fun publish(event: BtEvent) {
        eventQueue.trySend(event)
    }

    /**
     * 先同步发布 CLOSED，再尽力注销广播并失效所有所属 GATT/发现任务，最后停止自有作用域。
     * 幂等；缓存快照保留，但句柄后续无线操作失败，最终事件仅 tryEmit 不保证被收集。
     */
    override fun close() {
        val handles = synchronized(lock) {
            if (isClosed) return
            isClosed = true
            mutableAvailability.value = BtAvailability.CLOSED
            mutableAudioDevices.value = emptyList()
            cache.values.toList()
        }
        runCatching { context.unregisterReceiver(receiver) }
        discovery.invalidate(BtException.Disconnected())
        handles.forEach { it.gatt.invalidate(closed = true) }
        mutableEvents.tryEmit(BtEvent.OnBluetoothStateChanged(BtAvailability.CLOSED, this))
        eventQueue.close()
        scope.cancel()
    }

    private fun normalizeAddress(address: String): String {
        val value = address.uppercase()
        if (!BluetoothAdapter.checkBluetoothAddress(value)) throw BtException.InvalidDevice("Invalid Bluetooth address: $address")
        return value
    }
}
