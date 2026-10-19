package ink.lipoly.app.sunrise.drop.source

import ink.lipoly.app.sunrise.bluetooth.BtDevice
import ink.lipoly.app.sunrise.drop.ControllerContext
import ink.lipoly.app.sunrise.drop.DropException
import ink.lipoly.app.sunrise.drop.DropProtocol
import ink.lipoly.app.sunrise.drop.IBudsController
import ink.lipoly.app.sunrise.drop.SessionController
import ink.lipoly.app.sunrise.drop.openOwnedController

/** 9ECA-only controller; it does not fabricate GAIA battery or noise capabilities. */
internal class SourceController(
    context: ControllerContext,
    binding: SourceBinding,
) : SessionController(context), SourceControls by binding.controls

/**
 * Connect [device] and return a READY controller exposing [SourceControls] only. The 9ECA
 * command/response characteristic pair is required; firmware probing may remain incomplete
 * without changing the capability interfaces. Missing optional operations retain
 * [DropException.UnsupportedCapability] behavior.
 *
 * The controller owns its GATT session, never the Bluetooth manager. It does not scan, pair,
 * reconnect or open a classic transport. Failure or cancellation releases the connection before
 * rethrowing the original error; a missing 9ECA pair throws [DropException.UnsupportedDevice].
 */
suspend fun openSource(device: BtDevice): IBudsController = openOwnedController(
    device = device,
    requiredProtocol = DropProtocol.SOURCE_9ECA,
)
