package ink.lipoly.app.sunrise.drop.gaia

import ink.lipoly.app.sunrise.bluetooth.BtDevice
import ink.lipoly.app.sunrise.drop.AncMode
import ink.lipoly.app.sunrise.drop.BatteryControls
import ink.lipoly.app.sunrise.drop.ControllerContext
import ink.lipoly.app.sunrise.drop.DropException
import ink.lipoly.app.sunrise.drop.DropProfile
import ink.lipoly.app.sunrise.drop.DropProtocol
import ink.lipoly.app.sunrise.drop.EarbudBatteryReading
import ink.lipoly.app.sunrise.drop.IBudsController
import ink.lipoly.app.sunrise.drop.NoiseControls
import ink.lipoly.app.sunrise.drop.SessionController
import ink.lipoly.app.sunrise.drop.openOwnedController

/** GAIA capabilities for one epoch; optional feature probes do not change this interface shape. */
internal class GaiaController(
    context: ControllerContext,
    private val binding: GaiaBinding,
) : SessionController(context), GaiaControls by binding.controls, BatteryControls, NoiseControls {
    override suspend fun readBattery(): EarbudBatteryReading = binding.controls.getBattery()
    override suspend fun readNoise(): AncMode = binding.controls.getAncMode()
    override suspend fun writeNoise(mode: AncMode): AncMode = binding.controls.setAncMode(mode)
}

/**
 * Connect [device] and return a READY controller exposing [GaiaControls], [BatteryControls] and
 * [NoiseControls]. The GAIA command/response characteristic pair is required; optional feature
 * probes may remain incomplete, and unsupported operations still throw
 * [DropException.UnsupportedCapability]. No Source capabilities are installed by this factory.
 *
 * [profile] supplies ANC/DAC numbering. A classic EQ transport is obtained only from an explicit
 * [classicEqDevice]; a null RFCOMM handle retains the BLE EQ path. The controller owns the opened
 * GATT session and any supplied classic handle, not the Bluetooth manager. It does not scan,
 * pair or reconnect. Failure or cancellation releases these resources before rethrowing the
 * original error; a missing GAIA pair throws [DropException.UnsupportedDevice].
 */
suspend fun openGaia(
    device: BtDevice,
    profile: DropProfile,
    classicEqDevice: BtDevice? = null,
): IBudsController = openOwnedController(
    device = device,
    profile = profile,
    classicEq = classicEqDevice?.rfcomm,
    requiredProtocol = DropProtocol.GAIA_BLE,
)
