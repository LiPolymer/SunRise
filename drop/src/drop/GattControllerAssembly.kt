package ink.lipoly.app.sunrise.drop

import ink.lipoly.app.sunrise.bluetooth.BtDevice
import ink.lipoly.app.sunrise.bluetooth.GattController
import ink.lipoly.app.sunrise.bluetooth.GattPhase
import ink.lipoly.app.sunrise.bluetooth.GattSession
import ink.lipoly.app.sunrise.bluetooth.RfcommStream
import ink.lipoly.app.sunrise.drop.gaia.GaiaBinding
import ink.lipoly.app.sunrise.drop.gaia.GaiaController
import ink.lipoly.app.sunrise.drop.gaia.GaiaGattIds
import ink.lipoly.app.sunrise.drop.source.SourceBinding
import ink.lipoly.app.sunrise.drop.source.SourceController
import ink.lipoly.app.sunrise.drop.source.SourceGattIds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Lifecycle hooks for one GATT epoch plus its optional classic EQ handle. */
private fun gattLifecycle(
    gatt: GattController?,
    session: GattSession?,
    classicEq: RfcommStream?,
): SessionLifecycle {
    val isLive = {
        val snapshot = gatt?.state?.value
        snapshot == null || (snapshot.phase == GattPhase.CONNECTED && snapshot.session === session)
    }
    val lost = if (gatt != null && session != null) {
        gatt.state
            .filter { it.phase != GattPhase.CONNECTED || it.session !== session }
            .map { }
    } else emptyFlow()
    val release: suspend () -> Unit = {
        runCatching { classicEq?.close() }
        if (gatt != null) {
            if (session != null) {
                // Session.close compares epoch identity atomically in the GATT implementation.
                // A snapshot check followed by controller.disconnect could close a replacement.
                runCatching { session.close() }
            } else {
                runCatching { gatt.disconnect() }
            }
        }
    }
    return SessionLifecycle(isLive = isLive, lost = lost, release = release)
}

/** The single owned-connect path used by family and brand factories. */
internal suspend fun openOwnedController(
    device: BtDevice,
    profile: DropProfile? = null,
    classicEq: RfcommStream? = null,
    requiredProtocol: DropProtocol? = null,
): IBudsController {
    val gatt = device.gatt
    val session = try {
        gatt.connect()
    } catch (error: Throwable) {
        withContext(NonCancellable) { gattLifecycle(gatt, null, classicEq).release() }
        throw error
    }
    return openConnectedController(
        session = session,
        profile = profile,
        classicEq = classicEq,
        ownedGatt = gatt,
        ownedSession = session,
        requiredProtocol = requiredProtocol,
    )
}

/**
 * Assemble an already connected epoch. A required family selects only that family's capabilities;
 * without one, every discovered command/response pair participates in the single shared session.
 * Borrowed sessions are never disconnected, and [current] guards all old capability references.
 */
internal suspend fun openConnectedController(
    session: GattSession,
    profile: DropProfile? = null,
    classicEq: RfcommStream? = null,
    ownedGatt: GattController? = null,
    ownedSession: GattSession? = null,
    current: () -> Boolean = { true },
    requiredProtocol: DropProtocol? = null,
): IBudsController {
    var context: ControllerContext? = null
    var owner: SessionController? = null
    val ownedIdentity = if (ownedGatt != null) ownedSession ?: session else null
    try {
        fun hasPair(service: String, command: String, response: String): Boolean =
            session.characteristic(service, command) != null &&
                session.characteristic(service, response) != null

        val hasGaia = hasPair(GaiaGattIds.GAIA_SERVICE, GaiaGattIds.GAIA_COMMAND, GaiaGattIds.GAIA_RESPONSE)
        val hasSource = hasPair(SourceGattIds.SOURCE_SERVICE, SourceGattIds.SOURCE_COMMAND, SourceGattIds.SOURCE_RESPONSE)
        if ((requiredProtocol == DropProtocol.GAIA_BLE && !hasGaia) ||
            (requiredProtocol == DropProtocol.SOURCE_9ECA && !hasSource) || (!hasGaia && !hasSource)) {
            throw DropException.UnsupportedDevice()
        }
        val useGaia = hasGaia && requiredProtocol != DropProtocol.SOURCE_9ECA
        val useSource = hasSource && requiredProtocol != DropProtocol.GAIA_BLE
        val protocols = buildSet {
            if (useGaia) add(DropProtocol.GAIA_BLE)
            if (useSource) add(DropProtocol.SOURCE_9ECA)
        }
        val active = ControllerContext(
            protocols = protocols,
            lifecycle = gattLifecycle(ownedGatt, ownedIdentity, classicEq),
            current = current,
        )
        context = active
        val control = active.control
        val gaia = if (useGaia) {
            GaiaBinding(control, session, requireNotNull(profile) { "GAIA profile is required" }, classicEq)
        } else null
        val source = if (useSource) SourceBinding(control, session) else null
        val controller = when {
            gaia != null && source != null -> CombinedController(active, gaia, source)
            gaia != null -> GaiaController(active, gaia)
            source != null -> SourceController(active, source)
            else -> throw DropException.UnsupportedDevice()
        }
        owner = controller
        controller.observeLoss()
        control.checkActive()
        gaia?.subscribe()
        source?.subscribe()
        control.transaction { control.optionalOperation { session.requestMtu(247) } }
        gaia?.initialize()
        source?.initialize()
        currentCoroutineContext().ensureActive()
        control.mutate {
            it.copy(
                phase = DropPhase.READY,
                capabilities = it.capabilities.copy(complete = gaia?.complete != false && source?.complete != false),
            )
        }
        return controller
    } catch (error: Throwable) {
        val active = context
        val failure = if (active != null && error is Exception && error !is CancellationException) {
            active.control.normalizeTransport(error).also { normalized ->
                try {
                    if (active.control.isOpen) {
                        active.control.mutate { it.copy(phase = DropPhase.ERROR, error = normalized) }
                        active.control.emit(DropEvent.Error(normalized))
                    }
                } catch (_: DropException.Disconnected) {
                    // Retirement may overtake failure publication; preserve the original failure.
                }
            }
        } else error
        if (active != null) {
            (owner ?: SessionController(active)).close()
        } else {
            withContext(NonCancellable) { gattLifecycle(ownedGatt, ownedIdentity, classicEq).release() }
        }
        throw failure
    }
}
