package ink.lipoly.app.sunrise.blueConnector

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

internal class AndroidBtDiscovery(private val owner: AndroidBtManager) {
    private val scanMutex = Mutex()
    private val pendingLock = Any()
    private val profileRequests = mutableSetOf<CompletableDeferred<List<BluetoothDevice>>>()
    private val scans = mutableSetOf<Channel<ScanResult>>()

    internal fun invalidate(cause: BtException) = synchronized(pendingLock) {
        profileRequests.forEach { it.completeExceptionally(cause) }
        scans.forEach { it.close(cause) }
    }

    suspend fun connectedAudioDevices(): List<BluetoothDevice> {
        val adapter = owner.requireBluetooth()
        val devices = LinkedHashMap<String, BluetoothDevice>()
        for (profile in listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)) {
            for (device in profileDevices(adapter, profile)) devices[device.address.uppercase()] = device
        }
        return devices.values.sortedBy { it.address }
    }

    private suspend fun profileDevices(adapter: BluetoothAdapter, profile: Int): List<BluetoothDevice> {
        val answer = CompletableDeferred<List<BluetoothDevice>>()
        synchronized(pendingLock) {
            owner.requireBluetooth()
            profileRequests.add(answer)
        }
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(type: Int, proxy: BluetoothProfile) {
                try {
                    answer.complete(owner.platformOperation("enumerate connected audio devices") { proxy.connectedDevices })
                } catch (e: BtException) {
                    answer.completeExceptionally(e)
                } finally {
                    // Late callbacks still own a proxy, even after timeout or cancellation.
                    runCatching { adapter.closeProfileProxy(type, proxy) }
                }
            }
            override fun onServiceDisconnected(type: Int) { answer.complete(emptyList()) }
        }
        return try {
            if (!owner.platformOperation("open audio profile") { adapter.getProfileProxy(owner.context, listener, profile) }) {
                emptyList()
            } else {
                withTimeoutOrNull(1_500) { answer.await() } ?: emptyList()
            }
        } finally {
            synchronized(pendingLock) { profileRequests.remove(answer) }
            answer.cancel()
        }
    }

    suspend fun scanLe(name: String?, address: String?, timeoutMillis: Long): List<BtDevice> = scanMutex.withLock {
        val adapter = owner.requireBluetooth(scan = true)
        val scanner = owner.platformOperation("obtain LE scanner", scan = true) { adapter.bluetoothLeScanner }
            ?: return@withLock emptyList()
        val results = Channel<ScanResult>(64)
        val failure = MutableStateFlow<BtException?>(null)
        synchronized(pendingLock) {
            owner.requireBluetooth(scan = true)
            scans.add(results)
        }
        val callback = object : ScanCallback() {
            private fun fail(cause: BtException) {
                failure.compareAndSet(null, cause)
                results.close(cause)
            }
            private fun offer(result: ScanResult) {
                val sent = results.trySend(result)
                if (sent.isFailure && !sent.isClosed) {
                    fail(BtException.Transport("Bluetooth scan buffer overflow"))
                }
            }
            override fun onScanResult(callbackType: Int, result: ScanResult) { offer(result) }
            override fun onBatchScanResults(batch: MutableList<ScanResult>) { batch.forEach(::offer) }
            override fun onScanFailed(errorCode: Int) {
                fail(BtException.Transport("Bluetooth LE scan failed: $errorCode"))
            }
        }
        val found = LinkedHashMap<String, BtDevice>()
        try {
            owner.platformOperation("start LE scan", scan = true) { scanner.startScan(callback) }
            withTimeoutOrNull(timeoutMillis) {
                while (true) {
                    val hit = results.receive()
                    val hitName = owner.platformOperation("read scanned device name", scan = true) { hit.device.name }
                        ?: hit.scanRecord?.deviceName
                    val addressMatches = address != null && hit.device.address.equals(address, ignoreCase = true)
                    if ((name == null && address == null) || addressMatches || (name != null && hitName?.equals(name, ignoreCase = true) == true)) {
                        val device = owner.observe(hit.device, hitName)
                        found[device.address] = device
                        if (addressMatches) break
                    }
                }
            }
            // Channel.close retains buffered hits; early address completion must not hide scan failure.
            failure.value?.let { throw it }
            owner.requireBluetooth(scan = true)
            found.values.sortedBy { it.address }
        } finally {
            runCatching { scanner.stopScan(callback) }
            synchronized(pendingLock) { scans.remove(results) }
            results.close()
        }
    }
}
