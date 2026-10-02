package ink.lipoly.app.sunrise.blueConnector

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * 一次原生 GATT 的资源边界：稳定服务/特征、单 pending 槽、操作队列和通知发布 scope。
 *
 * guard 只保护原生身份、活动性和 pending；等待回调不持锁。每会话自有容量 64 的操作队列，
 * 原生读/写/CCCD 为 10 秒、MTU 为 5 秒；期限到达关闭会话，防止晚回调错配下一项。
 * 通知回调复制平台缓冲并投递容量 64 channel，溢出明确以 Transport 终止会话，不静默丢包。
 * @property device 本会话的稳定设备；scope 受其管理器拥有的 Job 管理。
 * @param onTerminated 同步报告失效；设备所有者必须按对象身份拒绝旧会话结果。
 */
internal class AndroidGattSession(
    override val device: AndroidBtDevice,
    private val onTerminated: (AndroidGattSession, BtException) -> Unit,
) : GattSession {
    private companion object {
        val ids = AtomicLong()
        val cccd: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val emptyValue = ByteArray(0)
    }

    override val id: Long = ids.incrementAndGet()
    private val guard = Any()
    private val job = SupervisorJob(device.owner.scope.coroutineContext[Job])
    private val scope = CoroutineScope(device.owner.scope.coroutineContext + job)
    private val operations = GattOperationQueue(scope)
    private val connected = CompletableDeferred<Unit>()
    private val discovered = CompletableDeferred<List<BluetoothGattService>>()
    private val notifications = Channel<GattEvent.ValueChanged>(64)
    private val mutableEvents = MutableSharedFlow<GattEvent>()
    override val events = mutableEvents.asSharedFlow()
    private val mutableMtu = MutableStateFlow(23)
    override val mtu = mutableMtu.asStateFlow()
    @Volatile private var serviceSnapshot: List<GattService> = emptyList()
    override val services: List<GattService> get() = serviceSnapshot
    private val handles = IdentityHashMap<BluetoothGattCharacteristic, Characteristic>()
    private var nativeGatt: BluetoothGatt? = null
    @Volatile private var closed = false
    private var pending: NativeOperation<*>? = null
    internal val isActive: Boolean get() = !closed

    /** 原生特征对象与所属会话双重身份；UUID 只用于查询，不能替代句柄归属验证。 */
    private class Characteristic(
        val session: AndroidGattSession,
        val native: BluetoothGattCharacteristic,
        override val serviceUuid: String,
    ) : GattCharacteristic {
        override val uuid: String = native.uuid.toString()
        override val properties: Set<GattProperty> = buildSet {
            val bits = native.properties
            if (bits and BluetoothGattCharacteristic.PROPERTY_READ != 0) add(GattProperty.READ)
            if (bits and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add(GattProperty.WRITE)
            if (bits and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add(GattProperty.WRITE_NO_RESPONSE)
            if (bits and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add(GattProperty.NOTIFY)
            if (bits and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add(GattProperty.INDICATE)
        }
    }

    /** 仅一个原生操作可等待回调；按类型及特征/描述符对象身份匹配，不按 UUID 粗略匹配。 */
    private sealed class NativeOperation<T : Any> {
        val result = CompletableDeferred<T>()
        class Read(val characteristic: BluetoothGattCharacteristic) : NativeOperation<ByteArray>()
        class Write(val characteristic: BluetoothGattCharacteristic) : NativeOperation<Unit>()
        class Descriptor(val descriptor: BluetoothGattDescriptor) : NativeOperation<Unit>()
        class Mtu : NativeOperation<Int>()
    }

    init {
        scope.launch {
            for (event in notifications) {
                if (!isActive) break
                // 慢收集者使发布挂起；有界回调 channel 饱和时显式关闭会话。
                mutableEvents.emit(event)
            }
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val accepted = synchronized(guard) {
                if (closed || (nativeGatt != null && nativeGatt !== gatt)) false
                else {
                    // 第一个系统回调可能先于 connectGatt 返回，允许这里先接纳原生对象。
                    if (nativeGatt == null) nativeGatt = gatt
                    true
                }
            }
            if (!accepted) return
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                connected.complete(Unit)
            } else if (status != BluetoothGatt.GATT_SUCCESS) {
                invalidate(BtException.Transport("GATT connection failed: $status"))
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                invalidate()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (!accepts(gatt)) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                discovered.completeExceptionally(BtException.Transport("GATT service discovery failed: $status"))
                return
            }
            try {
                val services = device.owner.platformOperation("read GATT services") { gatt.services.orEmpty().toList() }
                discovered.complete(services)
            } catch (failure: BtException) {
                invalidate(failure)
            }
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            deliver(gatt, characteristic, characteristic.value ?: return)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            deliver(gatt, characteristic, value)
        }

        /** 仅当前原生对象和已发现特征可投递；字节归本层所有，绝不逐通知启动无界协程。 */
        private fun deliver(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            val handle = synchronized(guard) {
                if (closed || nativeGatt !== gatt) return
                handles[characteristic] ?: return
            }
            // 平台可能复用回调数组，必须复制后交给异步发布队列。
            val sent = notifications.trySend(GattEvent.ValueChanged(handle, value.copyOf()))
            if (sent.isFailure && isActive)
                invalidate(BtException.Transport("GATT notification buffer overflow"))
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            synchronized(guard) {
                if (closed || nativeGatt !== gatt) return
                val operation = pending as? NativeOperation.Write ?: return
                if (operation.characteristic !== characteristic) return
                completeStatus(operation, status, "GATT write")
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            synchronized(guard) {
                if (closed || nativeGatt !== gatt) return
                val operation = pending as? NativeOperation.Descriptor ?: return
                if (operation.descriptor !== descriptor) return
                completeStatus(operation, status, "GATT descriptor write")
            }
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            @Suppress("DEPRECATION")
            completeRead(gatt, characteristic, characteristic.value ?: emptyValue, status)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            completeRead(gatt, characteristic, value, status)
        }

        private fun completeRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            synchronized(guard) {
                if (closed || nativeGatt !== gatt) return
                val operation = pending as? NativeOperation.Read ?: return
                if (operation.characteristic !== characteristic) return
                if (status == BluetoothGatt.GATT_SUCCESS) operation.result.complete(value.copyOf())
                else operation.result.completeExceptionally(BtException.Transport("GATT read failed: $status"))
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, value: Int, status: Int) {
            synchronized(guard) {
                if (closed || nativeGatt !== gatt) return
                val operation = pending as? NativeOperation.Mtu
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    mutableMtu.value = value
                    operation?.result?.complete(value)
                } else {
                    operation?.result?.completeExceptionally(BtException.Transport("GATT MTU request failed: $status"))
                }
            }
        }
    }

    /**
     * 显式非 autoConnect 的 LE 连接；连接及服务发现各等 12 秒，完成后冻结服务快照。
     * 不做协议探测、MTU 请求或订阅；发布 CONNECTED 由 [AndroidBtGatt] 在锁内核对归属后完成。
     */
    internal suspend fun open() {
        device.owner.requireBluetooth()
        @Suppress("DEPRECATION")
        val opened = synchronized(guard) {
            ensureActive()
            val gatt = device.owner.platformOperation("GATT connection") {
                if (Build.VERSION.SDK_INT >= 23)
                    device.nativeDevice.connectGatt(device.owner.context, false, callback, BluetoothDevice.TRANSPORT_LE)
                else device.nativeDevice.connectGatt(device.owner.context, false, callback)
            } ?: throw BtException.Transport("connectGatt returned null")
            if (closed || (nativeGatt != null && nativeGatt !== gatt)) {
                // 同步早到回调可能已接纳并关闭此对象，不能因返回握手重复释放。
                if (nativeGatt !== gatt) closeNative(gatt)
                throw BtException.Disconnected()
            }
            nativeGatt = gatt
            gatt
        }
        if (withTimeoutOrNull(12_000) { connected.await() } == null)
            throw BtException.Timeout("GATT connection")
        device.owner.requireBluetooth()
        val discoveryStarted = synchronized(guard) {
            ensureActive()
            device.owner.platformOperation("GATT service discovery") { opened.discoverServices() }
        }
        if (!discoveryStarted) throw BtException.Transport("GATT discovery could not start")
        val services = withTimeoutOrNull(12_000) { discovered.await() }
            ?: throw BtException.Timeout("GATT service discovery")
        val entries = IdentityHashMap<BluetoothGattCharacteristic, Characteristic>()
        val snapshot = services.map { service ->
            val uuid = service.uuid.toString()
            GattService(uuid, service.characteristics.orEmpty().map { characteristic ->
                entries.getOrPut(characteristic) { Characteristic(this, characteristic, uuid) }
            })
        }
        synchronized(guard) {
            ensureActive()
            handles.putAll(entries)
            serviceSnapshot = snapshot
        }
    }

    /** 校验会话身份及 READ，再入自有操作队列；回调返回值已复制，成功不是读取通知缓存。 */
    override suspend fun read(characteristic: GattCharacteristic): ByteArray {
        val handle = resolve(characteristic)
        if (GattProperty.READ !in handle.properties) throw BtException.UnsupportedOperation("GATT read")
        return nativeOperation(NativeOperation.Read(handle.native), "GATT read", 10_000) {
            it.readCharacteristic(handle.native)
        }
    }

    /**
     * 仅 WRITE_TYPE_DEFAULT 带响应写；入队前和开始时均检查 MTU-3，并复制调用方数组。
     * API 33+ 使用传值/状态码 API；旧版本先设置特征 value/writeType 再调用 Boolean API。
     */
    override suspend fun write(characteristic: GattCharacteristic, value: ByteArray) {
        val handle = resolve(characteristic)
        if (GattProperty.WRITE !in handle.properties)
            throw BtException.UnsupportedOperation("GATT write with response")
        if (value.size > mutableMtu.value - 3) throw BtException.PacketTooLarge()
        // 在等待入队前拥有负载副本；调用方可以随后复用其原始缓冲。
        val packet = value.copyOf()
        nativeOperation(NativeOperation.Write(handle.native), "GATT write", 10_000) { gatt ->
            if (packet.size > mutableMtu.value - 3) throw BtException.PacketTooLarge()
            if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeCharacteristic(handle.native, packet, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                handle.native.value = packet
                handle.native.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(handle.native)
            }
        }
    }

    /**
     * 串行设置本地通知开关并写 CCCD，NOTIFY 优先于 INDICATE；禁用不做订阅引用计数。
     * 缺属性/描述符抛 UnsupportedOperation，平台设置或写失败不保证回滚本地开关。
     */
    override suspend fun setNotifications(characteristic: GattCharacteristic, enabled: Boolean) {
        val handle = resolve(characteristic)
        val notify = GattProperty.NOTIFY in handle.properties
        if (!notify && GattProperty.INDICATE !in handle.properties)
            throw BtException.UnsupportedOperation("GATT notifications")
        val descriptor = handle.native.getDescriptor(cccd)
            ?: throw BtException.UnsupportedOperation("GATT CCCD")
        val value = when {
            !enabled -> BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            notify -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }
        nativeOperation(NativeOperation.Descriptor(descriptor), "GATT descriptor write", 10_000) { gatt ->
            if (!gatt.setCharacteristicNotification(handle.native, enabled))
                throw BtException.Transport("GATT notification setup failed")
            if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = value
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }
        }
    }

    /** 请求值必须为 23..517；返回回调实际值，协商期限 5 秒且超时使整个会话失效。 */
    override suspend fun requestMtu(value: Int): Int {
        require(value in 23..517) { "GATT MTU must be in 23..517" }
        ensureActive()
        return nativeOperation(NativeOperation.Mtu(), "GATT MTU request", 5_000) { it.requestMtu(value) }
    }

    /** 先检查有效性，再验证实现类型、所属会话和原生对象映射；旧会话句柄不可换绑。 */
    private fun resolve(characteristic: GattCharacteristic): Characteristic = synchronized(guard) {
        ensureActive()
        val handle = characteristic as? Characteristic ?: throw BtException.InvalidGattHandle()
        if (handle.session !== this || handles[handle.native] !== handle) throw BtException.InvalidGattHandle()
        handle
    }

    /**
     * worker 内安装 pending 并提交；调用方取消不取消已开始的原生等待。
     * withTimeoutOrNull 仅把自有期限转 Timeout，期限丢失使会话/队列整体失效；
     * finally 仅清理自身 pending，旧请求不得清除替代槽。
     */
    private suspend fun <T : Any> nativeOperation(
        request: NativeOperation<T>,
        operation: String,
        timeoutMillis: Long,
        start: (BluetoothGatt) -> Boolean,
    ): T = operations.run {
        device.owner.requireBluetooth()
        try {
            val accepted = synchronized(guard) {
                ensureActive()
                val gatt = nativeGatt ?: throw BtException.Disconnected()
                check(pending == null) { "GATT operations must be serialized" }
                pending = request
                device.owner.platformOperation(operation) { start(gatt) }
            }
            if (!accepted) throw BtException.Transport("$operation rejected")
            val result = withTimeoutOrNull(timeoutMillis) { request.result.await() }
            if (result == null) {
                val failure = BtException.Timeout(operation)
                // 原生 ATT 回调丢失后无法安全进入下一操作，必须失效整个会话。
                invalidate(failure)
                throw failure
            }
            result
        } finally {
            synchronized(guard) {
                if (pending === request) pending = null
            }
        }
    }

    private fun accepts(gatt: BluetoothGatt): Boolean = synchronized(guard) {
        !closed && nativeGatt === gatt
    }

    private fun ensureActive() {
        if (closed || device.owner.isClosed) throw BtException.Disconnected()
    }

    private fun completeStatus(operation: NativeOperation<Unit>, status: Int, name: String) {
        if (status == BluetoothGatt.GATT_SUCCESS) operation.result.complete(Unit)
        else operation.result.completeExceptionally(BtException.Transport("$name failed: $status"))
    }

    /** 使用 Disconnected 同步失效；不关闭所属管理器，旧会话关闭也不能更改替代会话状态。 */
    override fun close() = invalidate()

    /**
     * guard 内一次性标记 closed 并失败化所有直接等待，锁外关闭队列/通知/scope/原生资源。
     * nativeGatt 保留身份以防早到 callback 与 connectGatt 返回握手导致重复释放。
     */
    internal fun invalidate(cause: BtException = BtException.Disconnected()) {
        val previous = synchronized(guard) {
            if (closed) return
            closed = true
            connected.completeExceptionally(cause)
            discovered.completeExceptionally(cause)
            pending?.result?.completeExceptionally(cause)
            pending = null
            // 保留身份，防止早到回调与连接返回握手重复关闭同一个原生对象。
            nativeGatt
        }
        operations.close(cause)
        notifications.cancel()
        scope.cancel()
        closeNative(previous)
        onTerminated(this, cause)
    }

    private fun closeNative(gatt: BluetoothGatt?) {
        if (gatt == null) return
        // 无线关闭或权限撤销后仍必须尽力释放原生资源，关闭异常不向外抛。
        runCatching { gatt.disconnect() }
        runCatching { gatt.close() }
    }
}
