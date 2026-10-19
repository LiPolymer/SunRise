package ink.lipoly.app.sunrise.drop

import ink.lipoly.app.sunrise.bluetooth.GattCharacteristic
import ink.lipoly.app.sunrise.bluetooth.GattSession

/** Finds one characteristic by its fully lowercase service and characteristic UUIDs. */
internal fun GattSession.characteristic(service: String, uuid: String): GattCharacteristic? =
    services.firstOrNull { it.uuid == service }?.characteristics?.firstOrNull { it.uuid == uuid }
