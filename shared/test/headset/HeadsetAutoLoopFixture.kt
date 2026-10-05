package ink.lipoly.app.sunrise.headset

import ink.lipoly.app.sunrise.blueConnector.*
import ink.lipoly.app.sunrise.drop.GaiaGattDeviceFixture
import ink.lipoly.app.sunrise.drop.GaiaIds
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/** Audio enumeration and counted GATT lifecycles around the existing byte-level GAIA device fixture. */
internal class HeadsetAutoLoopFixture : BtManager {
    override val availability = MutableStateFlow(BtAvailability.ENABLED)
    override val devices = MutableStateFlow<List<BtDevice>>(emptyList())
    override val connectedAudioDevices = MutableStateFlow<List<BtDevice>>(emptyList())
    override val events = MutableSharedFlow<BtEvent>(extraBufferCapacity = 16)
    val refreshes = MutableStateFlow(0)
    private var nextSessionId = 0L
    private val associations = object : HeadsetAssociations {
        private val endpoints = mutableMapOf<String, String>()
        override fun endpoint(address: String) = endpoints[address]
        override fun remember(address: String, endpoint: String) { endpoints[address] = endpoint }
    }
    val client = HeadsetClient(this, associations)

    inner class Device(override val address: String, name: String?) : BtDevice {
        override val manager: BtManager get() = this@HeadsetAutoLoopFixture
        override val rfcomm: BtRfcomm? = null
        override val info = MutableStateFlow(BtDeviceInfo(name, BtDeviceKind.DUAL, BtBondState.BONDED))
        val connects = MutableStateFlow(0)
        val disconnects = MutableStateFlow(0)
        var storage: GaiaGattDeviceFixture? = null
            private set
        override val gatt = object : BtGatt {
            override val state = MutableStateFlow(GattState())
            override suspend fun connect(): GattSession {
                connects.value++
                state.value.session?.let { return it }
                val bytes = GaiaGattDeviceFixture(id = ++nextSessionId)
                storage = bytes
                val session = object : GattSession by bytes {
                    override val device: BtDevice get() = this@Device
                }
                state.value = GattState(GattPhase.CONNECTED, session)
                return session
            }
            override suspend fun disconnect() {
                disconnects.value++
                state.value.session?.close()
                state.value = GattState()
            }
        }
        override fun requestBond(): Boolean = true
    }

    fun add(address: String, name: String?): Device = Device(address, name).also {
        devices.value = devices.value + it
    }

    suspend fun audio(vararg selected: Device) {
        connectedAudioDevices.value = selected.toList()
        selected.firstOrNull()?.let { events.emit(BtEvent.OnDeviceChanged(it, this)) }
    }

    override fun device(address: String): BtDevice =
        devices.value.firstOrNull { it.address.equals(address, ignoreCase = true) }
            ?: throw BtException.InvalidDevice("Unknown fixture address: $address")

    override suspend fun refreshConnectedAudioDevices(): List<BtDevice> {
        val result = connectedAudioDevices.value
        refreshes.value++
        return result
    }
    override suspend fun bondedDevices(): List<BtDevice> = devices.value
    override suspend fun scanLe(name: String?, address: String?, timeoutMillis: Long): List<BtDevice> =
        devices.value.filter { (name != null && it.info.value.name.equals(name, ignoreCase = true)) ||
            (address != null && it.address.equals(address, ignoreCase = true)) }

    suspend fun awaitRefresh(after: Int = 0) = withTimeout(8_000) { refreshes.first { it > after } }
    suspend fun awaitPhase(phase: HeadsetPhase): HeadsetState =
        withTimeout(8_000) { client.state.first { it.phase == phase } }


    suspend fun dispose() {
        client.disconnect()
        client.close()
        close()
        devices.value.forEach { handle ->
            check((handle as Device).storage?.commands?.none {
                it.feature == GaiaIds.MUSIC_PROCESSING && it.command == GaiaIds.Eq.SET_USER_CONFIG
            } != false) { "Selection/reference scenarios must never send an EQ configuration SET" }
        }
    }
    override fun close() {
        devices.value.forEach { it.gatt.state.value.session?.close() }
        availability.value = BtAvailability.CLOSED
    }
}
