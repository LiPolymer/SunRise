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
import kotlin.time.Duration.Companion.milliseconds

/**
 * 管理器自有的音频 profile 枚举与串行 LE 扫描，不占用任何设备 GATT 队列。
 *
 * pendingLock 只管理可被无线关闭/管理器关闭失败化的请求集合，不持锁等待平台回调；
 * 扫描回调投递容量 64 的 channel，溢出明确失败，而非静默丢弃发现。
 */
internal class AndroidBtDiscovery(private val owner: AndroidBtManager) {
    private val scanMutex = Mutex()
    private val pendingLock = Any()
    private val profileRequests = mutableSetOf<CompletableDeferred<List<BluetoothDevice>>>()
    private val scans = mutableSetOf<Channel<ScanResult>>()

    /** 无线不可用/关闭时立即失败化正在等待的 profile 和扫描；最终资源释放仍在各 finally 中。 */
    internal fun invalidate(cause: BtException) = synchronized(pendingLock) {
        profileRequests.forEach { it.completeExceptionally(cause) }
        scans.forEach { it.close(cause) }
    }

    /**
     * 顺序查询 A2DP 和 HEADSET，按大写地址合并并排序；不做品牌、协议或双地址关联。
     * profile 代理被拒绝、服务断开或 1,500 毫秒内未返回时，该 profile 贡献空列表。
     */
    suspend fun connectedAudioDevices(): List<BluetoothDevice> {
        val adapter = owner.enforceBluetoothPermission()
        val devices = LinkedHashMap<String, BluetoothDevice>()
        for (profile in listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)) {
            for (device in profileDevices(adapter, profile)) devices[device.address.uppercase()] = device
        }
        return devices.values.sortedBy { it.address }
    }

    /** 注册可失效的代理请求；晚到回调也必须关闭自身 proxy，取消不能转移其资源所有权。 */
    private suspend fun profileDevices(adapter: BluetoothAdapter, profile: Int): List<BluetoothDevice> {
        val answer = CompletableDeferred<List<BluetoothDevice>>()
        synchronized(pendingLock) {
            owner.enforceBluetoothPermission()
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
                withTimeoutOrNull(1_500.milliseconds) { answer.await() } ?: emptyList()
            }
        } finally {
            synchronized(pendingLock) { profileRequests.remove(answer) }
            answer.cancel()
        }
    }

    /**
     * 持扫描锁完成单次扫描；名称/地址 OR 匹配，命中地址可提前结束。
     * null scanner 和正常期限返回已找到列表；onScanFailed/溢出保存失败，即使提前命中也不掩盖。
     * 调用者取消透传；finally 尽力 stopScan 并注销请求。过滤掉的结果不进入 manager.observe。
     */
    suspend fun scanLe(name: String?, address: String?, timeoutMillis: Long): List<BtDevice> = scanMutex.withLock {
        val adapter = owner.enforceBluetoothPermission(scan = true)
        val scanner = owner.platformOperation("obtain LE scanner", scan = true) { adapter.bluetoothLeScanner }
            ?: return@withLock emptyList()
        val results = Channel<ScanResult>(64)
        val failure = MutableStateFlow<BtException?>(null)
        synchronized(pendingLock) {
            owner.enforceBluetoothPermission(scan = true)
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
            withTimeoutOrNull(timeoutMillis.milliseconds) {
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
            owner.enforceBluetoothPermission(scan = true)
            found.values.sortedBy { it.address }
        } finally {
            runCatching { scanner.stopScan(callback) }
            synchronized(pendingLock) { scans.remove(results) }
            results.close()
        }
    }
}
