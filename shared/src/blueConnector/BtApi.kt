package ink.lipoly.app.sunrise.blueConnector

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class BtAvailability { UNKNOWN, UNAVAILABLE, DISABLED, ENABLED, CLOSED }
enum class BtDeviceKind { UNKNOWN, CLASSIC, LE, DUAL }
enum class BtBondState { NONE, BONDING, BONDED }
data class BtDeviceInfo(
    val name: String? = null,
    val kind: BtDeviceKind = BtDeviceKind.UNKNOWN,
    val bondState: BtBondState = BtBondState.NONE,
)
interface BtManager {
    val availability: StateFlow<BtAvailability>
    val devices: StateFlow<List<BtDevice>>
    val connectedAudioDevices: StateFlow<List<BtDevice>>
    val events: SharedFlow<BtEvent>
    fun device(address: String): BtDevice
    suspend fun refreshConnectedAudioDevices(): List<BtDevice>
    suspend fun bondedDevices(): List<BtDevice>
    suspend fun scanLe(name: String? = null, address: String? = null, timeoutMillis: Long = 8_000): List<BtDevice>
    fun close()
}
interface BtDevice {
    val manager: BtManager
    val address: String
    val info: StateFlow<BtDeviceInfo>
    val gatt: BtGatt
    fun requestBond(): Boolean
}
enum class GattPhase { DISCONNECTED, CONNECTING, CONNECTED, ERROR, CLOSED }
data class GattState(
    val phase: GattPhase = GattPhase.DISCONNECTED,
    val session: GattSession? = null,
    val error: BtException? = null,
)
interface BtGatt {
    val state: StateFlow<GattState>
    suspend fun connect(): GattSession
    suspend fun disconnect()
}
enum class GattProperty { READ, WRITE, WRITE_NO_RESPONSE, NOTIFY, INDICATE }
interface GattCharacteristic {
    val serviceUuid: String
    val uuid: String
    val properties: Set<GattProperty>
}
data class GattService(val uuid: String, val characteristics: List<GattCharacteristic>)
interface GattSession {
    val id: Long
    val device: BtDevice
    val services: List<GattService>
    val mtu: StateFlow<Int>
    val events: SharedFlow<GattEvent>
    suspend fun read(characteristic: GattCharacteristic): ByteArray
    suspend fun write(characteristic: GattCharacteristic, value: ByteArray)
    suspend fun setNotifications(characteristic: GattCharacteristic, enabled: Boolean)
    suspend fun requestMtu(value: Int = 247): Int
    fun close()
}
sealed interface BtEvent {
    data class OnDiscovered(val device: BtDevice, val sender: BtManager) : BtEvent
    data class OnDeviceChanged(val device: BtDevice, val sender: BtManager) : BtEvent
    data class OnBluetoothStateChanged(val availability: BtAvailability, val sender: BtManager) : BtEvent
    data class OnGattStateChanged(val device: BtDevice, val state: GattState, val sender: BtManager) : BtEvent
    data class OnError(val cause: BtException, val sender: BtManager, val device: BtDevice? = null) : BtEvent
}
sealed interface GattEvent {
    data class ValueChanged(val characteristic: GattCharacteristic, val value: ByteArray) : GattEvent
}
sealed class BtException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class BluetoothUnavailable : BtException("Bluetooth is unavailable or disabled")
    class MissingPermission(val permissions: Set<String>) : BtException("Missing Bluetooth permissions: ${permissions.joinToString()}")
    class InvalidDevice(message: String) : BtException(message)
    class Disconnected : BtException("Bluetooth session is disconnected")
    class Timeout(val operation: String) : BtException("Bluetooth operation timed out: $operation")
    class UnsupportedOperation(val operation: String) : BtException("Unsupported Bluetooth operation: $operation")
    class InvalidGattHandle : BtException("GATT characteristic belongs to another session")
    class PacketTooLarge : BtException("Packet exceeds negotiated GATT MTU")
    class Transport(message: String, cause: Throwable? = null) : BtException(message, cause)
}
