package ink.lipoly.app.sunrise.drop.moondrop

import ink.lipoly.app.sunrise.bluetooth.BtDevice
import ink.lipoly.app.sunrise.bluetooth.GattSession
import ink.lipoly.app.sunrise.bluetooth.RfcommStream
import ink.lipoly.app.sunrise.drop.DropOptions
import ink.lipoly.app.sunrise.drop.DropProfile
import ink.lipoly.app.sunrise.drop.IBudsController
import ink.lipoly.app.sunrise.drop.openConnectedController
import ink.lipoly.app.sunrise.drop.openOwnedController

/**
 * Connect [device] once, resolve the Moondrop numbering profile, and return a READY controller
 * exposing exactly the discovered GAIA and/or 9ECA command/response families. GAIA additionally
 * exposes battery and noise controls; a 9ECA-only device does not. Optional feature-probe failure
 * does not remove a family's capability interfaces.
 *
 * The returned controller owns this GATT session and any classic EQ handle, not the manager.
 * It does not scan, pair or reconnect. Failure or cancellation releases owned resources before
 * rethrowing the original error. No recognized pair throws
 * [ink.lipoly.app.sunrise.drop.DropException.UnsupportedDevice].
 *
 * @param device Actual communications endpoint.
 * @param options ANC/DAC numbering overrides.
 * @param profileDevice Configuration address/name identity. A classic EQ handle is obtained only
 * when its address differs from the communications endpoint; no SPP record is guessed otherwise.
 */
suspend fun openMoondrop(
    device: BtDevice,
    options: DropOptions = DropOptions(),
    profileDevice: BtDevice = device,
): IBudsController = openOwnedController(
    device = device,
    profile = DropProfiles.resolve(options, profileDevice.address, profileDevice.info.value.name),
    classicEq = if (profileDevice.address != device.address) profileDevice.rfcomm else null,
)

/** Borrow an existing epoch for brand assembly without disconnecting its caller-owned GATT. */
internal suspend fun openMoondrop(
    session: GattSession,
    profile: DropProfile,
    classicEq: RfcommStream? = null,
    current: () -> Boolean = { true },
): IBudsController = openConnectedController(
    session = session,
    profile = profile,
    classicEq = classicEq,
    current = current,
)
