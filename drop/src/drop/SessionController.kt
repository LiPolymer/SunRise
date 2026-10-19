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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Construction state shared with delegated family interfaces before their owner is created. */
internal class ControllerContext(
    session: GattSession,
    protocols: Set<DropProtocol>,
    val ownedGatt: GattController?,
    val ownedSession: GattSession?,
    val classicEq: RfcommStream?,
    private val current: () -> Boolean,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val closed = MutableStateFlow(false)
    val mutableState = MutableStateFlow(DropState())
    private val mutableEvents = MutableSharedFlow<DropEvent>(extraBufferCapacity = 32)
    val state = mutableState.asStateFlow()
    val events = mutableEvents.asSharedFlow()
    private val isCurrent: Boolean get() {
        if (closed.value || !current()) return false
        val snapshot = ownedGatt?.state?.value ?: return true
        return snapshot.phase == GattPhase.CONNECTED && snapshot.session === ownedSession
    }
    val control = ControlSession(
        session = session,
        parentScope = scope,
        protocols = protocols,
        current = { isCurrent },
        publishState = { if (isCurrent) mutableState.value = it },
        publishEvent = { if (isCurrent) mutableEvents.tryEmit(it) },
    )
}

/** Common flow, retirement and transport ownership; protocol work stays in family bindings. */
internal open class SessionController(private val context: ControllerContext) : IBudsController {
    final override val state = context.state
    final override val events = context.events

    internal fun observeIdentity() {
        context.ownedGatt?.let { gatt ->
            context.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                gatt.state.collect { snapshot ->
                    if (!context.closed.value &&
                        (snapshot.phase != GattPhase.CONNECTED || snapshot.session !== context.ownedSession)) {
                        close()
                    }
                }
            }
        }
    }

    final override suspend fun close() {
        if (!context.closed.compareAndSet(expect = false, update = true)) return
        context.control.close()
        context.scope.cancel()
        context.mutableState.value = DropState()
        releaseOwnedTransport(context.ownedGatt, context.ownedSession, context.classicEq)
    }
}

/** Cleanup never replaces an initialization/cancellation failure or disconnects a newer session. */
private suspend fun releaseOwnedTransport(
    gatt: GattController?,
    session: GattSession?,
    classicEq: RfcommStream?,
) = withContext(NonCancellable) {
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
        releaseOwnedTransport(gatt, null, classicEq)
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
        fun hasPair(service: String, command: String, response: String): Boolean {
            val characteristics = session.services.firstOrNull { it.uuid == service }?.characteristics
                ?: return false
            return characteristics.any { it.uuid == command } && characteristics.any { it.uuid == response }
        }
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
        val active = ControllerContext(session, protocols, ownedGatt, ownedIdentity, classicEq, current)
        context = active
        val control = active.control
        val gaia = if (useGaia) GaiaBinding(control, requireNotNull(profile) { "GAIA profile is required" }, classicEq) else null
        val source = if (useSource) SourceBinding(control) else null
        val controller = when {
            gaia != null && source != null -> CombinedController(active, gaia, source)
            gaia != null -> GaiaController(active, gaia)
            source != null -> SourceController(active, source)
            else -> throw DropException.UnsupportedDevice()
        }
        owner = controller
        controller.observeIdentity()
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
        if (active != null) (owner ?: SessionController(active)).close()
        else releaseOwnedTransport(ownedGatt, ownedIdentity, classicEq)
        throw failure
    }
}
