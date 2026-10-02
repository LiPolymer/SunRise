package ink.lipoly.app.sunrise.blueConnector

import android.bluetooth.BluetoothDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class AndroidBtDevice(
    internal val owner: AndroidBtManager,
    internal val nativeDevice: BluetoothDevice,
    override val address: String,
) : BtDevice {
    override val manager: BtManager get() = owner
    private val mutableInfo = MutableStateFlow(BtDeviceInfo())
    override val info = mutableInfo.asStateFlow()
    override val gatt = AndroidBtGatt(this)

    internal fun updateInfo(value: BtDeviceInfo): Boolean {
        if (mutableInfo.value == value) return false
        mutableInfo.value = value
        return true
    }

    override fun requestBond(): Boolean {
        owner.requireBluetooth()
        return owner.platformOperation("request bond") { nativeDevice.createBond() }
    }
}
