package ink.lipoly.app.sunrise.blueConnector

import ink.lipoly.app.sunrise.drop.DropGattIds
import ink.lipoly.app.sunrise.drop.JvmMockGaiaPeripheral
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 桌面进程内模拟器；每个管理器独占地址缓存、外设存储和连接编号，不访问真实蓝牙。
 *
 * 所有操作都立即完成。单一短生命周期锁串行化外设操作、通知发布和同步关闭，
 * 不启动后台任务；已发布的 Flow 事件仍遵循普通缓冲语义，不重放给晚订阅者。
 */
internal class JvmMockBtManager : BtManager {
    private companion object {
        const val DEVICE_ADDRESS = "02:00:00:00:00:01"
        const val DEVICE_NAME = "MOONDROP MOCA"
        val addressPattern = Regex("[0-9A-F]{2}(:[0-9A-F]{2}){5}")
        val deviceInfo = BtDeviceInfo(DEVICE_NAME, BtDeviceKind.DUAL, BtBondState.BONDED)
    }

    private val lifecycle = Any()
    private var closed = false
    private var nextSessionId = 0L
    private val cache = LinkedHashMap<String, Device>()
    private val mutableAvailability = MutableStateFlow(BtAvailability.ENABLED)
    private val mutableDevices = MutableStateFlow<List<BtDevice>>(emptyList())
    private val mutableAudioDevices = MutableStateFlow<List<BtDevice>>(emptyList())
    private val mutableEvents = MutableSharedFlow<BtEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val availability = mutableAvailability.asStateFlow()
    override val devices = mutableDevices.asStateFlow()
    override val connectedAudioDevices = mutableAudioDevices.asStateFlow()
    override val events = mutableEvents.asSharedFlow()

    override fun device(address: String): BtDevice {
        val normalized = normalizeAddress(address)
        return synchronized(lifecycle) {
            ensureOpen()
            handle(normalized)
        }
    }

    override suspend fun refreshConnectedAudioDevices(): List<BtDevice> {
        currentCoroutineContext().ensureActive()
        return synchronized(lifecycle) {
            ensureOpen()
            listOf(observe()).also { mutableAudioDevices.value = it }
        }
    }

    override suspend fun bondedDevices(): List<BtDevice> {
        currentCoroutineContext().ensureActive()
        return synchronized(lifecycle) {
            ensureOpen()
            listOf(observe())
        }
    }

    override suspend fun scanLe(name: String?, address: String?, timeoutMillis: Long): List<BtDevice> {
        require(timeoutMillis > 0) { "Scan timeout must be positive" }
        val normalized = address?.let(::normalizeAddress)
        currentCoroutineContext().ensureActive()
        return synchronized(lifecycle) {
            ensureOpen()
            val matches = name == null && normalized == null ||
                name?.equals(DEVICE_NAME, ignoreCase = true) == true || normalized == DEVICE_ADDRESS
            if (matches) listOf(observe()) else emptyList()
        }
    }

    override fun close() {
        synchronized(lifecycle) {
            if (closed) return
            closed = true
            mutableAvailability.value = BtAvailability.CLOSED
            mutableAudioDevices.value = emptyList()
            cache.values.forEach { it.gatt.closeManager() }
            publish(BtEvent.OnBluetoothStateChanged(BtAvailability.CLOSED, this))
        }
    }

    private fun normalizeAddress(address: String): String {
        val normalized = address.uppercase()
        if (!addressPattern.matches(normalized))
            throw BtException.InvalidDevice("Invalid Bluetooth address: $address")
        return normalized
    }

    private fun ensureOpen() {
        if (closed) throw BtException.Disconnected()
    }

    /** 只缓存身份，不发发现事件；有效但未配置的地址没有外设存储或虚构信息。 */
    private fun handle(address: String): Device = cache[address] ?: Device(address).also {
        cache[address] = it
        mutableDevices.value = cache.values.sortedBy { device -> device.address }
    }

    private fun observe(): Device = handle(DEVICE_ADDRESS).also {
        if (!it.observed) {
            it.observed = true
            publish(BtEvent.OnDiscovered(it, this))
        }
    }

    private fun publish(event: BtEvent) {
        mutableEvents.tryEmit(event)
    }

    private inner class Device(override val address: String) : BtDevice {
        override val manager: BtManager get() = this@JvmMockBtManager
        override val rfcomm: RfcommStream? = null
        val peripheral = if (address == DEVICE_ADDRESS) JvmMockGaiaPeripheral() else null
        private val mutableInfo = MutableStateFlow(if (peripheral != null) deviceInfo else BtDeviceInfo())
        override val info = mutableInfo.asStateFlow()
        override val gatt = Gatt(this)
        var observed = false

        override fun requestBond(): Boolean = synchronized(lifecycle) {
            ensureOpen()
            peripheral != null
        }
    }

    /** 新连接只替换会话，不重建设备上的协议存储；旧 close 按对象身份拒绝回写。 */
    private inner class Gatt(private val device: Device) : BtGatt {
        private val mutableState = MutableStateFlow(GattState())
        override val state = mutableState.asStateFlow()
        private var current: Session? = null

        override suspend fun connect(): GattSession {
            currentCoroutineContext().ensureActive()
            return synchronized(lifecycle) {
                ensureOpen()
                current?.let { return it }
                if (device.peripheral == null) {
                    val failure = BtException.Transport("No simulated Bluetooth device at ${device.address}")
                    publishState(GattState(GattPhase.ERROR, error = failure))
                    throw failure
                }
                Session(++nextSessionId, device).also {
                    current = it
                    publishState(GattState(GattPhase.CONNECTED, it))
                }
            }
        }

        override suspend fun disconnect() {
            currentCoroutineContext().ensureActive()
            synchronized(lifecycle) {
                current?.retire()
                publishState(GattState(if (closed) GattPhase.CLOSED else GattPhase.DISCONNECTED))
            }
        }

        fun closeManager() {
            current?.retire()
            publishState(GattState(GattPhase.CLOSED))
        }

        fun sessionTerminated(session: Session, error: BtException?) {
            if (current !== session) return
            current = null
            publishState(when {
                closed -> GattState(GattPhase.CLOSED)
                error != null -> GattState(GattPhase.ERROR, error = error)
                else -> GattState()
            })
        }

        private fun publishState(value: GattState) {
            if (mutableState.value == value) return
            mutableState.value = value
            publish(BtEvent.OnGattStateChanged(device, value, this@JvmMockBtManager))
            value.error?.let { publish(BtEvent.OnError(it, this@JvmMockBtManager, device)) }
        }
    }

    /** 会话专属句柄没有结构相等性；相同 UUID 不能绕过旧会话/外部句柄检查。 */
    private class Characteristic(
        override val uuid: String,
        override val properties: Set<GattProperty>,
    ) : GattCharacteristic {
        override val serviceUuid = DropGattIds.GAIA_SERVICE
    }

    private inner class Session(override val id: Long, override val device: Device) : GattSession {
        private val command = Characteristic(DropGattIds.GAIA_COMMAND, setOf(GattProperty.WRITE))
        private val response = Characteristic(DropGattIds.GAIA_RESPONSE, setOf(GattProperty.NOTIFY))
        override val services = listOf(GattService(DropGattIds.GAIA_SERVICE, listOf(command, response)))
        private val mutableMtu = MutableStateFlow(23)
        override val mtu = mutableMtu.asStateFlow()
        private val mutableEvents = MutableSharedFlow<GattEvent>(extraBufferCapacity = 64)
        override val events = mutableEvents.asSharedFlow()
        private var retired = false
        private var notificationsEnabled = false

        override suspend fun read(characteristic: GattCharacteristic): ByteArray {
            currentCoroutineContext().ensureActive()
            return synchronized(lifecycle) {
                validate(characteristic)
                throw BtException.UnsupportedOperation("Simulated GAIA characteristics are write/notify only")
            }
        }

        override suspend fun write(characteristic: GattCharacteristic, value: ByteArray) {
            currentCoroutineContext().ensureActive()
            synchronized(lifecycle) {
                validate(characteristic)
                if (GattProperty.WRITE !in characteristic.properties)
                    throw BtException.UnsupportedOperation("GATT write")
                if (value.size > mutableMtu.value - 3) throw BtException.PacketTooLarge()
                val bytes = device.peripheral!!.handle(value)
                if (bytes != null && notificationsEnabled) {
                    // 发布与关闭共用锁，没有稍后才运行的通知任务或失效会话回包。
                    if (!mutableEvents.tryEmit(GattEvent.ValueChanged(response, bytes))) {
                        val failure = BtException.Transport("GATT notification buffer overflow")
                        retire(failure)
                        throw failure
                    }
                }
            }
        }

        override suspend fun setNotifications(characteristic: GattCharacteristic, enabled: Boolean) {
            currentCoroutineContext().ensureActive()
            synchronized(lifecycle) {
                validate(characteristic)
                if (GattProperty.NOTIFY !in characteristic.properties && GattProperty.INDICATE !in characteristic.properties)
                    throw BtException.UnsupportedOperation("GATT notifications")
                notificationsEnabled = enabled
            }
        }

        override suspend fun requestMtu(value: Int): Int {
            require(value in 23..517) { "GATT MTU must be in 23..517" }
            currentCoroutineContext().ensureActive()
            return synchronized(lifecycle) {
                ensureActive()
                mutableMtu.value = value
                value
            }
        }

        override fun close() {
            synchronized(lifecycle) { retire() }
        }

        fun retire(error: BtException? = null) {
            if (retired) return
            retired = true
            notificationsEnabled = false
            device.gatt.sessionTerminated(this, error)
        }

        private fun ensureActive() {
            ensureOpen()
            if (retired) throw BtException.Disconnected()
        }

        private fun validate(characteristic: GattCharacteristic) {
            ensureActive()
            if (characteristic !== command && characteristic !== response) throw BtException.InvalidGattHandle()
        }
    }
}
