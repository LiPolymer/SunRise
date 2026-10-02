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
                // Suspends for slow collectors; the bounded callback channel supplies backpressure.
                mutableEvents.emit(event)
            }
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val accepted = synchronized(guard) {
                if (closed || (nativeGatt != null && nativeGatt !== gatt)) false
                else {
                    // Android may deliver its first callback before connectGatt has returned.
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

        private fun deliver(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            val handle = synchronized(guard) {
                if (closed || nativeGatt !== gatt) return
                handles[characteristic] ?: return
            }
            // The platform owns/reuses callback arrays. No unbounded per-notification coroutine.
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
                // A synchronous first callback may already have adopted and closed this handle.
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

    override suspend fun read(characteristic: GattCharacteristic): ByteArray {
        val handle = resolve(characteristic)
        if (GattProperty.READ !in handle.properties) throw BtException.UnsupportedOperation("GATT read")
        return nativeOperation(NativeOperation.Read(handle.native), "GATT read", 10_000) {
            it.readCharacteristic(handle.native)
        }
    }

    override suspend fun write(characteristic: GattCharacteristic, value: ByteArray) {
        val handle = resolve(characteristic)
        if (GattProperty.WRITE !in handle.properties)
            throw BtException.UnsupportedOperation("GATT write with response")
        if (value.size > mutableMtu.value - 3) throw BtException.PacketTooLarge()
        // Own the packet before waiting in the queue; callers may reuse their buffer meanwhile.
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

    override suspend fun requestMtu(value: Int): Int {
        require(value in 23..517) { "GATT MTU must be in 23..517" }
        ensureActive()
        return nativeOperation(NativeOperation.Mtu(), "GATT MTU request", 5_000) { it.requestMtu(value) }
    }

    private fun resolve(characteristic: GattCharacteristic): Characteristic = synchronized(guard) {
        ensureActive()
        val handle = characteristic as? Characteristic ?: throw BtException.InvalidGattHandle()
        if (handle.session !== this || handles[handle.native] !== handle) throw BtException.InvalidGattHandle()
        handle
    }

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
                // Losing an ATT callback makes the connection unsafe for the next queued operation.
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

    override fun close() = invalidate()

    internal fun invalidate(cause: BtException = BtException.Disconnected()) {
        val previous = synchronized(guard) {
            if (closed) return
            closed = true
            connected.completeExceptionally(cause)
            discovered.completeExceptionally(cause)
            pending?.result?.completeExceptionally(cause)
            pending = null
            // Retain its identity so an early callback/return handshake cannot close it twice.
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
        // Closing must still release native resources after radio/permission loss.
        runCatching { gatt.disconnect() }
        runCatching { gatt.close() }
    }
}
