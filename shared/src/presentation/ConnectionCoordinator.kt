package ink.lipoly.app.sunrise.presentation

import ink.lipoly.app.sunrise.catalog.Catalog
import ink.lipoly.app.sunrise.catalog.CatalogSnapshot
import ink.lipoly.app.sunrise.catalog.matchesCatalogDevice
import ink.lipoly.app.sunrise.headset.HeadsetClient
import ink.lipoly.app.sunrise.headset.HeadsetDevice
import ink.lipoly.app.sunrise.settings.UiSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Process-owned policy. Presentation attachment never restarts a connection epoch. */
internal class ConnectionCoordinator(
    val client: HeadsetClient?,
    private val catalog: Catalog,
    private val settings: UiSettingsStore,
    private val scope: CoroutineScope,
) {
    private val guard = Any()
    private val mutablePermissions = MutableStateFlow<Set<String>?>(null)
    val permissions: StateFlow<Set<String>?> = mutablePermissions.asStateFlow()
    private var subscription: Job? = null
    private var closed = false
    private var autoStarted = false
    private var permissionRestartPending = false
    private var previousPermissions: Set<String>? = null
    private var hasPolicy = false
    private var policySnapshot: CatalogSnapshot? = null
    private var policyLoading = true
    private var policyCatalogOnly = true

    fun start() = synchronized(guard) {
        if (closed || subscription != null) return@synchronized
        client?.setAutoDeviceFilter { false }
        subscription = scope.launch {
            combine(catalog.state, settings.state, permissions) { catalogState, uiSettings, missing ->
                Policy(catalogState.snapshot, catalogState.loading, uiSettings.catalogOnlyDevices, missing)
            }.collect { policy ->
                synchronized(guard) {
                    if (closed) return@synchronized
                    val active = client
                    if (!hasPolicy || policy.snapshot !== policySnapshot ||
                        policy.loading != policyLoading || policy.catalogOnly != policyCatalogOnly) {
                        hasPolicy = true
                        policySnapshot = policy.snapshot
                        policyLoading = policy.loading
                        policyCatalogOnly = policy.catalogOnly
                        val accept: (HeadsetDevice) -> Boolean = { device ->
                            !policy.loading && (!policy.catalogOnly || matchesCatalogDevice(policy.snapshot, device.name))
                        }
                        active?.setAutoDeviceFilter(accept)
                    }
                    if (autoStarted && policy.missing?.isNotEmpty() == true) permissionRestartPending = true
                    if (previousPermissions?.isNotEmpty() == true && policy.missing?.isEmpty() == true) {
                        permissionRestartPending = true
                    }
                    previousPermissions = policy.missing
                    if (active != null && !policy.loading && policy.missing?.isEmpty() == true &&
                        (!autoStarted || permissionRestartPending)) {
                        active.startAutoConnect()
                        autoStarted = true
                        permissionRestartPending = false
                    }
                }
            }
        }
    }

    fun updatePermissions(missing: Set<String>) = synchronized(guard) {
        if (!closed) mutablePermissions.value = missing.toSet()
    }

    fun retry() = synchronized(guard) {
        if (!closed && permissions.value?.isEmpty() == true && !catalog.state.value.loading) {
            client?.startAutoConnect()
        }
    }

    /** Cancels only policy observation; the application resource owner closes the facade. */
    fun close() = synchronized(guard) {
        if (closed) return@synchronized
        closed = true
        subscription?.cancel()
    }

    private data class Policy(
        val snapshot: CatalogSnapshot?,
        val loading: Boolean,
        val catalogOnly: Boolean,
        val missing: Set<String>?,
    )
}
