package ink.lipoly.app.sunrise.di

import ink.lipoly.app.sunrise.catalog.Catalog
import ink.lipoly.app.sunrise.catalog.CatalogDocuments
import ink.lipoly.app.sunrise.presentation.CatalogOperations
import ink.lipoly.app.sunrise.presentation.EqSessionOwner
import ink.lipoly.app.sunrise.presentation.HeadsetPresentationController
import ink.lipoly.app.sunrise.presentation.PresentationSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.core.parameter.parametersOf
import ink.lipoly.app.sunrise.presentation.ConnectionCoordinator
import ink.lipoly.app.sunrise.settings.UiSettingsStore
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.core.KoinApplication

/** An isolated application graph. Hosts attach to it without owning its services. */
class SunRiseRuntime internal constructor(
    private val application: KoinApplication,
    internal val catalog: Catalog,
    internal val settings: UiSettingsStore,
    internal val coordinator: ConnectionCoordinator,
    internal val lifetime: ApplicationLifetime,
    environment: CatalogEnvironment,
) {
    internal val client get() = coordinator.client
    private val closeMutex = Mutex()
    private val closed = MutableStateFlow(false)
    private val initialization = lifetime.scope.async(start = CoroutineStart.LAZY) {
        catalog.init(
            storage = environment.storage,
            loadBundled = environment.loadBundled,
            fetcher = environment.fetcher,
            dispatcher = environment.dispatcher,
        )
    }

    init {
        coordinator.start()
        initialization.start()
    }

    suspend fun awaitInitialized() {
        initialization.await()
    }

    fun updatePermissions(missing: Set<String>) {
        coordinator.updatePermissions(missing)
    }

    internal fun createPresentationSession(
        documents: CatalogDocuments,
        parentScope: CoroutineScope,
        preview: Boolean,
    ): PresentationSession {
        check(!closed.value) { "SunRise runtime is closed" }
        val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
        val context = PresentationContext(documents, scope, preview)
        var headset: HeadsetPresentationController? = null
        var operations: CatalogOperations? = null
        var eq: EqSessionOwner? = null
        try {
            val resolvedHeadset = application.koin.get<HeadsetPresentationController> { parametersOf(context) }
            headset = resolvedHeadset
            val resolvedOperations = application.koin.get<CatalogOperations> { parametersOf(context) }
            operations = resolvedOperations
            val resolvedEq = application.koin.get<EqSessionOwner> { parametersOf(context) }
            eq = resolvedEq
            check(!closed.value) { "SunRise runtime is closed" }
            return PresentationSession(resolvedHeadset, resolvedOperations, resolvedEq, scope)
        } catch (failure: Throwable) {
            try { eq?.close() } catch (closeFailure: Throwable) { failure.addSuppressed(closeFailure) }
            try { operations?.close() } catch (closeFailure: Throwable) { failure.addSuppressed(closeFailure) }
            try { headset?.close() } catch (closeFailure: Throwable) { failure.addSuppressed(closeFailure) }
            scope.cancel()
            throw failure
        }
    }

    /** Call from outside the application scope, so joining that scope cannot join the caller. */
    suspend fun close() = withContext(NonCancellable) {
        closeMutex.withLock {
            if (!closed.compareAndSet(expect = false, update = true)) return@withLock
            var failure: Throwable? = null
            fun record(error: Throwable) {
                val first = failure
                if (first == null) failure = error else first.addSuppressed(error)
            }
            try {
                coordinator.close()
            } catch (error: Throwable) {
                record(error)
            }
            try {
                initialization.cancelAndJoin()
            } catch (error: Throwable) {
                record(error)
            }
            try {
                catalog.close()
            } catch (error: Throwable) {
                record(error)
            }
            try {
                lifetime.scope.coroutineContext[Job]!!.cancelAndJoin()
            } catch (error: Throwable) {
                record(error)
            } finally {
                try {
                    application.close()
                } catch (error: Throwable) {
                    record(error)
                }
            }
            failure?.let { throw it }
        }
    }
}

/** Resolve everything before launching initialization, making failed assembly synchronous to clean up. */
internal fun assembleRuntime(application: KoinApplication): SunRiseRuntime {
    var lifetime: ApplicationLifetime? = null
    var coordinator: ConnectionCoordinator? = null
    try {
        val resolvedLifetime = application.koin.get<ApplicationLifetime>()
        lifetime = resolvedLifetime
        val catalog = application.koin.get<Catalog>()
        val settings = application.koin.get<UiSettingsStore>()
        val environment = application.koin.get<CatalogEnvironment>()
        val resolvedCoordinator = application.koin.get<ConnectionCoordinator>()
        coordinator = resolvedCoordinator
        return SunRiseRuntime(application, catalog, settings, resolvedCoordinator, resolvedLifetime, environment)
    } catch (failure: Throwable) {
        try {
            coordinator?.close()
        } catch (closeFailure: Throwable) {
            failure.addSuppressed(closeFailure)
        }
        lifetime?.scope?.cancel()
        closeApplicationAfterFailure(application, failure)
        throw failure
    }
}

internal fun closeApplicationAfterFailure(application: KoinApplication, failure: Throwable) {
    try {
        application.close()
    } catch (closeFailure: Throwable) {
        failure.addSuppressed(closeFailure)
    }
}
