package ink.lipoly.app.sunrise.drop

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Permission checks are public so an Android host can request permissions before starting a client. */
object DropPermissions {
    fun missing(context: Context, scan: Boolean = true): Set<String> = buildSet {
        if (Build.VERSION.SDK_INT >= 31) {
            if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            if (scan && context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                add(Manifest.permission.BLUETOOTH_SCAN)
        } else if (scan && context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
}

internal class AndroidDiscovery(private val context: Context) {
    private val manager: BluetoothManager? = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    val adapter: BluetoothAdapter? get() = manager?.adapter

    fun requireBluetooth(scan: Boolean = false): BluetoothAdapter {
        val missing = DropPermissions.missing(context, scan)
        if (missing.isNotEmpty()) throw DropException.MissingPermission(missing)
        val a = adapter ?: throw DropException.BluetoothUnavailable()
        if (!a.isEnabled) throw DropException.BluetoothUnavailable()
        return a
    }

    suspend fun connectedAudioDevices(): List<BluetoothDevice> {
        val a = requireBluetooth()
        val devices = LinkedHashMap<String, BluetoothDevice>()
        for (profile in listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)) {
            for (d in profileDevices(a, profile)) devices[d.address.uppercase()] = d
        }
        return devices.values.sortedBy { it.address }
    }

    private suspend fun profileDevices(adapter: BluetoothAdapter, profile: Int): List<BluetoothDevice> {
        val answer = CompletableDeferred<List<BluetoothDevice>>()
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(type: Int, proxy: BluetoothProfile) {
                answer.complete(runCatching { proxy.connectedDevices }.getOrDefault(emptyList()))
                runCatching { adapter.closeProfileProxy(type, proxy) }
            }
            override fun onServiceDisconnected(type: Int) { answer.complete(emptyList()) }
        }
        return try {
            if (!adapter.getProfileProxy(context, listener, profile)) return emptyList()
            withTimeoutOrNull(1_500) { answer.await() } ?: emptyList()
        } catch (e: CancellationException) { throw e
        } catch (_: SecurityException) { throw DropException.MissingPermission(DropPermissions.missing(context, false))
        } catch (_: Exception) { emptyList() }
    }

    fun bondedLeWithName(name: String?): List<BluetoothDevice> {
        if (name.isNullOrBlank()) return emptyList()
        val a = requireBluetooth()
        return a.bondedDevices.filter {
            (it.type == BluetoothDevice.DEVICE_TYPE_LE || it.type == BluetoothDevice.DEVICE_TYPE_DUAL) &&
                it.name?.equals(name, ignoreCase = true) == true
        }
    }

    suspend fun scanLe(name: String?, address: String): List<BluetoothDevice> = withContext(Dispatchers.IO) {
        val a = requireBluetooth(scan = true)
        val scanner = a.bluetoothLeScanner ?: return@withContext emptyList()
        val found = LinkedHashMap<String, BluetoothDevice>()
        val results = Channel<ScanResult>(Channel.UNLIMITED)
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) { results.trySend(result) }
            override fun onBatchScanResults(batch: MutableList<ScanResult>) { batch.forEach { results.trySend(it) } }
        }
        try {
            scanner.startScan(callback)
            withTimeoutOrNull(8_000) {
                while (true) {
                    val hit = results.receive()
                    val hitName = runCatching { hit.device.name }.getOrNull() ?: hit.scanRecord?.deviceName
                    if (hit.device.address.equals(address, true) || hitName?.equals(name, true) == true) {
                        found[hit.device.address.uppercase()] = hit.device
                        if (hit.device.address.equals(address, true)) break
                    }
                }
            }
        } finally {
            runCatching { scanner.stopScan(callback) }
            results.close()
        }
        found.values.toList()
    }
}
