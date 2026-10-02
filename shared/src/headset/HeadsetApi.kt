package ink.lipoly.app.sunrise.headset

import ink.lipoly.app.sunrise.blueConnector.BtDevice
import ink.lipoly.app.sunrise.drop.DropEvent
import ink.lipoly.app.sunrise.drop.DropState

enum class HeadsetPhase {
    IDLE, DISCOVERING, CONNECTING, PROBING, READY, RECONNECTING, SELECTION_REQUIRED, ERROR
}

/** Name is an observation snapshot; verified means only that an address association was remembered. */
data class HeadsetDevice(val device: BtDevice, val name: String?, val verified: Boolean = false) {
    val address: String get() = device.address
}

data class HeadsetState(
    val phase: HeadsetPhase = HeadsetPhase.IDLE,
    val device: HeadsetDevice? = null,
    val controls: DropState = DropState(),
    val error: Exception? = null,
)

sealed interface HeadsetEvent {
    data class Control(val event: DropEvent) : HeadsetEvent
    data class Error(val cause: Exception) : HeadsetEvent
}

internal interface HeadsetAssociations {
    fun endpoint(address: String): String?
    fun remember(address: String, endpoint: String)
}
