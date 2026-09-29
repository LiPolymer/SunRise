package ink.lipoly.app.sunrise.drop

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID

internal class AndroidGattLink(
    private val context: Context,
    private val device: BluetoothDevice,
    private val onGaia: (ByteArray) -> Unit,
    private val onSource: (ByteArray) -> Unit,
    private val onLost: () -> Unit,
) {
    companion object {
        val GAIA_SERVICE: UUID = UUID.fromString("00001100-d102-11e1-9b23-00025b00a5a5")
        private val GAIA_COMMAND: UUID = UUID.fromString("00001101-d102-11e1-9b23-00025b00a5a5")
        private val GAIA_RESPONSE: UUID = UUID.fromString("00001102-d102-11e1-9b23-00025b00a5a5")
        private val GAIA_DATA: UUID = UUID.fromString("00001103-d102-11e1-9b23-00025b00a5a5")
        val SOURCE_SERVICE: UUID = UUID.fromString("9eca0000-7f3a-4f32-9a38-a91b2c6e0100")
        private val SOURCE_COMMAND: UUID = UUID.fromString("9eca0001-7f3a-4f32-9a38-a91b2c6e0100")
        private val SOURCE_RESPONSE: UUID = UUID.fromString("9eca0002-7f3a-4f32-9a38-a91b2c6e0100")
        private val SOURCE_NOTIFICATION: UUID = UUID.fromString("9eca0003-7f3a-4f32-9a38-a91b2c6e0100")
        private val SOURCE_CAPABILITY: UUID = UUID.fromString("9eca0004-7f3a-4f32-9a38-a91b2c6e0100")
        private val SOURCE_INFO: UUID = UUID.fromString("9eca0005-7f3a-4f32-9a38-a91b2c6e0100")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val operations = Mutex()
    private val guard = Any()
    private val connected = CompletableDeferred<Unit>()
    private val services = CompletableDeferred<List<BluetoothGattService>>()
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var closed = false
    @Volatile private var mtu = 23
    @Volatile private var pendingWrite: CompletableDeferred<Int>? = null
    @Volatile private var pendingDescriptor: CompletableDeferred<Int>? = null
    @Volatile private var pendingRead: Pair<UUID, CompletableDeferred<ByteArray>>? = null
    @Volatile private var pendingMtu: CompletableDeferred<Int>? = null
    private var gaiaCommand: BluetoothGattCharacteristic? = null
    private var sourceCommand: BluetoothGattCharacteristic? = null
    private var sourceCapability: BluetoothGattCharacteristic? = null
    private var sourceInfo: BluetoothGattCharacteristic? = null

    val hasGaia: Boolean get() = gaiaCommand != null
    val hasSource: Boolean get() = sourceCommand != null
    val hasSourceCapability: Boolean get() = sourceCapability != null
    val hasSourceInfo: Boolean get() = sourceInfo != null

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            val accepted = synchronized(guard) {
                if (closed || (gatt != null && g !== gatt)) false
                else { if (gatt == null) gatt = g; true }
            }
            if (!accepted) return
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                connected.complete(Unit)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                val failure = DropException.Disconnected()
                if (!connected.isCompleted) connected.completeExceptionally(failure)
                if (!services.isCompleted) services.completeExceptionally(failure)
                pendingWrite?.completeExceptionally(failure)
                pendingDescriptor?.completeExceptionally(failure)
                pendingRead?.second?.completeExceptionally(failure)
                pendingMtu?.completeExceptionally(failure)
                onLost()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (closed || g !== gatt || services.isCompleted) return
            if (status == BluetoothGatt.GATT_SUCCESS) services.complete(g.services.orEmpty())
            else services.completeExceptionally(DropException.Transport("GATT service discovery failed: $status"))
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            deliver(g, ch, ch.value ?: return)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            deliver(g, ch, value)
        }

        private fun deliver(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            if (closed || g !== gatt) return
            when (ch.uuid) {
                GAIA_RESPONSE, GAIA_DATA -> onGaia(value.copyOf())
                SOURCE_RESPONSE, SOURCE_NOTIFICATION -> onSource(value.copyOf())
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            if (!closed && g === gatt) pendingWrite?.complete(status)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (!closed && g === gatt) pendingDescriptor?.complete(status)
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION")
            completeRead(g, ch, ch.value ?: byteArrayOf(), status)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            completeRead(g, ch, value, status)
        }

        private fun completeRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (closed || g !== gatt) return
            val pending = pendingRead ?: return
            if (pending.first != ch.uuid) return
            if (status == BluetoothGatt.GATT_SUCCESS) pending.second.complete(value.copyOf())
            else pending.second.completeExceptionally(DropException.Transport("GATT read failed: $status"))
        }

        override fun onMtuChanged(g: BluetoothGatt, value: Int, status: Int) {
            if (closed || g !== gatt) return
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = value
            pendingMtu?.complete(mtu)
        }
    }

    suspend fun open(): Set<DropProtocol> {
        try {
            @Suppress("DEPRECATION")
            val openedGatt = if (Build.VERSION.SDK_INT >= 23)
                device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            else device.connectGatt(context, false, callback)
            if (openedGatt == null) throw DropException.Transport("connectGatt returned null")
            synchronized(guard) {
                if (closed) {
                    openedGatt.close()
                    throw DropException.Disconnected()
                }
                gatt = openedGatt
            }
            withTimeout(12_000) { connected.await() }
            if (openedGatt.discoverServices() != true) throw DropException.Transport("GATT discovery could not start")
            val discovered = withTimeout(12_000) { services.await() }
            val gaia = discovered.firstOrNull { it.uuid == GAIA_SERVICE }
            val source = discovered.firstOrNull { it.uuid == SOURCE_SERVICE }
            gaiaCommand = gaia?.getCharacteristic(GAIA_COMMAND)
                ?.takeIf { gaia.getCharacteristic(GAIA_RESPONSE) != null }
            sourceCommand = source?.getCharacteristic(SOURCE_COMMAND)
                ?.takeIf { source.getCharacteristic(SOURCE_RESPONSE) != null }
            sourceCapability = source?.getCharacteristic(SOURCE_CAPABILITY)
            sourceInfo = source?.getCharacteristic(SOURCE_INFO)
            if (!hasGaia && !hasSource) throw DropException.UnsupportedDevice()
            if (hasGaia) {
                notify(gaia?.getCharacteristic(GAIA_RESPONSE))
                try { notify(gaia?.getCharacteristic(GAIA_DATA)) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Data notifications are optional. */ }
            }
            if (hasSource) {
                notify(source?.getCharacteristic(SOURCE_RESPONSE))
                try { notify(source?.getCharacteristic(SOURCE_NOTIFICATION)) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Unsolicited notifications are optional. */ }
            }
            try { requestMtu() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* The default MTU is sufficient for command frames. */ }
            return buildSet {
                if (hasGaia) add(DropProtocol.GAIA_BLE)
                if (hasSource) add(DropProtocol.SOURCE_9ECA)
            }
        } catch (e: Throwable) {
            close()
            throw when (e) {
                is DropException -> e
                is kotlinx.coroutines.TimeoutCancellationException -> DropException.Timeout("GATT connection")
                is CancellationException -> e
                is SecurityException -> DropException.MissingPermission(setOf("BLUETOOTH_CONNECT"))
                else -> DropException.Transport("GATT connection failed", e)
            }
        }
    }

    private suspend fun notify(ch: BluetoothGattCharacteristic?) {
        if (ch == null) return
        val g = gatt ?: throw DropException.Disconnected()
        if (!g.setCharacteristicNotification(ch, true)) throw DropException.Transport("GATT notification setup failed")
        val descriptor = ch.getDescriptor(CCCD) ?: throw DropException.Transport("GATT CCCD missing")
        operations.withLock {
            val result = CompletableDeferred<Int>()
            pendingDescriptor = result
            try {
                val value = if (ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                val accepted = if (Build.VERSION.SDK_INT >= 33)
                    g.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
                else {
                    @Suppress("DEPRECATION")
                    descriptor.value = value
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(descriptor)
                }
                if (!accepted) throw DropException.Transport("GATT descriptor write rejected")
                if (withTimeout(10_000) { result.await() } != BluetoothGatt.GATT_SUCCESS)
                    throw DropException.Transport("GATT descriptor write failed")
            } finally { pendingDescriptor = null }
        }
    }

    private suspend fun requestMtu() = operations.withLock {
        val g = gatt ?: return@withLock
        val result = CompletableDeferred<Int>()
        pendingMtu = result
        try {
            if (g.requestMtu(247)) withTimeout(5_000) { result.await() }
        } finally { pendingMtu = null }
    }

    suspend fun writeGaia(bytes: ByteArray) = write(gaiaCommand, bytes)
    suspend fun writeSource(bytes: ByteArray) = write(sourceCommand, bytes)

    private suspend fun write(ch: BluetoothGattCharacteristic?, bytes: ByteArray) {
        val g = gatt ?: throw DropException.Disconnected()
        if (ch == null) throw DropException.UnsupportedCapability("GATT command characteristic")
        if (bytes.size > mtu - 3) throw DropException.Protocol("Packet exceeds negotiated GATT MTU")
        operations.withLock {
            val result = CompletableDeferred<Int>()
            pendingWrite = result
            try {
                val accepted = if (Build.VERSION.SDK_INT >= 33)
                    g.writeCharacteristic(ch, bytes.copyOf(), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
                else {
                    @Suppress("DEPRECATION")
                    ch.value = bytes.copyOf()
                    ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    @Suppress("DEPRECATION")
                    g.writeCharacteristic(ch)
                }
                if (!accepted) throw DropException.Transport("GATT write rejected")
                if (withTimeout(10_000) { result.await() } != BluetoothGatt.GATT_SUCCESS)
                    throw DropException.Transport("GATT write failed")
            } finally { pendingWrite = null }
        }
    }

    suspend fun readCapability(): ByteArray = read(sourceCapability)
    suspend fun readInfo(): ByteArray = read(sourceInfo)

    private suspend fun read(ch: BluetoothGattCharacteristic?): ByteArray {
        val g = gatt ?: throw DropException.Disconnected()
        if (ch == null) throw DropException.UnsupportedCapability("9ECA readable characteristic")
        return operations.withLock {
            val result = CompletableDeferred<ByteArray>()
            pendingRead = ch.uuid to result
            try {
                if (!g.readCharacteristic(ch)) throw DropException.Transport("GATT read rejected")
                withTimeout(10_000) { result.await() }
            } finally { pendingRead = null }
        }
    }

    fun close() {
        val previous = synchronized(guard) {
            if (closed) return
            closed = true
            gatt.also { gatt = null }
        }
        val failure = DropException.Disconnected()
        pendingWrite?.completeExceptionally(failure)
        pendingDescriptor?.completeExceptionally(failure)
        pendingRead?.second?.completeExceptionally(failure)
        pendingMtu?.completeExceptionally(failure)
        runCatching { previous?.disconnect() }
        runCatching { previous?.close() }
    }
}
